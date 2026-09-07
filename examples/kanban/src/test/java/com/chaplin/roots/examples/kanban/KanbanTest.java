package com.chaplin.roots.examples.kanban;

import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.ValidationException;
import com.chaplin.roots.examples.kanban.TaskRepository.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KanbanTest {
    static final AuthenticatedIdentity EDITOR=AuthenticatedIdentity.of("test-editor",Set.of("ROLE_EDITOR"));
    static final AuthenticatedIdentity VIEWER=AuthenticatedIdentity.of("test-viewer",Set.of("ROLE_VIEWER"));
    static final String PASSWORD="local-test-password-at-least-24-characters";
    @TempDir Path temp;
    private String schema;
    private String url;
    private String user;
    private String password;

    @BeforeEach void database() throws Exception {
        String target=System.getenv("KANBAN_TEST_POSTGRES_URL");
        user=Objects.requireNonNullElse(System.getenv("KANBAN_TEST_POSTGRES_USER"),"");
        password=Objects.requireNonNullElse(System.getenv("KANBAN_TEST_POSTGRES_PASSWORD"),"");
        if(target!=null) {
            if(!java.net.URI.create(target.substring(5)).getPath().equals("/roots_kanban_test") || target.contains("currentSchema"))
                throw new IllegalArgumentException("Use dedicated roots_kanban_test database without currentSchema");
            schema="kanban_test_"+UUID.randomUUID().toString().replace("-","");
            try(var c=java.sql.DriverManager.getConnection(target,user,password);var s=c.createStatement()) { s.execute("CREATE SCHEMA "+schema); }
            url=target+(target.contains("?") ? "&" : "?")+"currentSchema="+schema;
        } else url="jdbc:h2:file:"+temp.resolve("kanban").toString().replace('\\','/');
        try(var pool=pool()) { Application.migrations(pool).migrate(); }
    }
    @AfterEach void cleanup() throws Exception {
        if(schema!=null && schema.matches("kanban_test_[a-f0-9]{32}")) {
            try(var c=java.sql.DriverManager.getConnection(System.getenv("KANBAN_TEST_POSTGRES_URL"),user,password);var s=c.createStatement()) { s.execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
    HikariDataSource pool() {
        var config=new HikariConfig(); config.setJdbcUrl(url); config.setUsername(user); config.setPassword(password); config.setMaximumPoolSize(4); return new HikariDataSource(config);
    }
    static Fields fields(String title,Status status) { return new Fields(title,"Useful context",status,Priority.HIGH,"Kyle",LocalDate.of(2026,10,12)); }

    @Test void durableWritesConflictsArchiveAndPermissions() throws Exception {
        String id;
        try(var pool=pool()) {
            var repo=new TaskRepository(pool);
            var operation=UUID.randomUUID();
            id=repo.create(EDITOR,operation,fields("Fix retries",Status.READY));
            assertEquals(id,repo.create(EDITOR,operation,fields("Fix retries",Status.READY)));
            assertEquals(1,repo.list(VIEWER,"",false).size());
            repo.move(EDITOR,id,1,Status.IN_PROGRESS);
            assertThrows(Conflict.class,()->repo.save(EDITOR,id,1,fields("Stale edit",Status.DONE)));
            assertThrows(SecurityException.class,()->repo.move(VIEWER,id,2,Status.DONE));
            assertThrows(SecurityException.class,()->repo.list(AuthenticatedIdentity.named("anonymous"),"",false));
            assertEquals(Status.IN_PROGRESS,repo.get(VIEWER,id).fields().status());
            assertEquals(2,repo.activity(VIEWER,id).size());
            repo.archive(EDITOR,id,2,true);
            assertEquals(0,repo.list(VIEWER,"",false).size());
            assertEquals(1,repo.list(VIEWER,"",true).size());
            assertThrows(Conflict.class,()->repo.save(EDITOR,id,3,fields("Archived edit",Status.DONE)));
            repo.archive(EDITOR,id,3,false);
            assertEquals(1,repo.list(VIEWER,"Kyle",false).size());
            assertEquals(0,repo.list(VIEWER,"%",false).size());
            assertThrows(ValidationException.class,()->fields(" ",Status.READY));
        }
        try(var reopened=pool()) { assertEquals(4,new TaskRepository(reopened).get(VIEWER,id).version()); }
    }

    @Test void competingMovesOnlyOneCommits() throws Exception {
        try(var pool=pool()) {
            var repo=new TaskRepository(pool); var id=repo.create(EDITOR,UUID.randomUUID(),fields("Concurrent task",Status.READY));
            var start=new java.util.concurrent.CountDownLatch(1);
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures=java.util.stream.IntStream.range(0,8).mapToObj(i -> executor.submit(() -> {
                    start.await(); try { repo.move(EDITOR,id,1,Status.DONE); return true; } catch(Conflict e) { return false; }
                })).toList();
                start.countDown(); int committed=0; for(var future:futures) if(future.get()) committed++;
                assertEquals(1,committed); assertEquals(2,repo.activity(VIEWER,id).size());
            }
        }
    }

    @Test void browserCreatesEditsDragsSearchesArchivesAndPreservesStaleTyping() throws Exception {
        try(var context=new SpringApplicationBuilder(Application.class).run(
                "--server.port=0","--KANBAN_AUTH=local","--KANBAN_SECURE_COOKIES=false","--KANBAN_ADMIN_PASSWORD="+PASSWORD,
                "--KANBAN_JDBC_URL="+url,"--KANBAN_JDBC_USER="+user,"--KANBAN_JDBC_PASSWORD="+password,
                "--spring.main.banner-mode=off","--logging.level.root=WARN","--roots.shutdown-timeout=1s","--spring.lifecycle.timeout-per-shutdown-phase=2s")) {
            var base="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var repo=context.getBean(TaskRepository.class);
            try(var client=java.net.http.HttpClient.newHttpClient()) {
                var protectedPage=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/app/")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(302,protectedPage.statusCode());
                var missingCsrf=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/login"))
                    .header("Content-Type","application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString("username=admin&password="+PASSWORD)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(403,missingCsrf.statusCode());
            }
            seed(repo);
            var browser=new ChromeDriver(new ChromeOptions().addArguments("--headless=new","--no-sandbox","--disable-dev-shm-usage","--window-size=1440,1000"));
            try {
                var wait=new WebDriverWait(browser,Duration.ofSeconds(15));
                browser.get(base+"/app/");
                assertTrue(browser.getCurrentUrl().contains("/login"));
                screenshot(browser,"login-desktop");
                browser.findElement(By.name("username")).sendKeys("admin");
                browser.findElement(By.name("password")).sendKeys(PASSWORD);
                browser.findElement(By.cssSelector("button[type=submit]")).click();
                wait.until(d -> !d.findElements(By.id("new-task")).isEmpty());
                screenshot(browser,"board-desktop");
                browser.findElement(By.id("new-task")).click();
                wait.until(d -> !d.findElements(By.name("title")).isEmpty());
                browser.findElement(By.name("title")).sendKeys("Browser acceptance task");
                browser.findElement(By.cssSelector("textarea[name=description]")).sendKeys("Verify persistence, keyboard moves, and drag-and-drop.");
                browser.findElement(By.name("assignee")).sendKeys("Kyle");
                new Select(browser.findElement(By.cssSelector(".task-form [name=status]"))).selectByValue("READY");
                click(browser,"Create task");
                wait.until(d -> !d.findElements(By.linkText("Browser acceptance task")).isEmpty() && d.findElements(By.name("title")).isEmpty());
                var task=repo.list(EDITOR,"Browser acceptance",false).getFirst();
                browser.findElement(By.linkText("Browser acceptance task")).click();
                wait.until(d -> !d.findElements(By.name("title")).isEmpty());
                browser.findElement(By.name("title")).sendKeys(Keys.END," edited");
                new Select(browser.findElement(By.cssSelector(".task-form [name=status]"))).selectByValue("IN_PROGRESS");
                screenshot(browser,"editor-desktop");
                click(browser,"Save changes");
                wait.until(d -> repo.get(EDITOR,task.id()).version()==2 && d.findElements(By.name("title")).isEmpty());
                // Native HTML5 drag events exercise the widget and Roots hidden-form bridge.
                wait.until(d -> "true".equals(d.findElement(By.id("board")).getDomAttribute("data-ready")));
                ((JavascriptExecutor)browser).executeScript("""
                    const card=document.querySelector('[data-task-id="'+arguments[0]+'"]');
                    const column=document.querySelector('[data-status=DONE]');
                    const data=new DataTransfer();
                    card.dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:data}));
                    column.dispatchEvent(new DragEvent('dragover',{bubbles:true,cancelable:true,dataTransfer:data}));
                    column.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:data}));
                    """,task.id());
                wait.until(d -> repo.get(EDITOR,task.id()).fields().status()==Status.DONE);
                browser.navigate().refresh();
                assertTrue(browser.findElement(By.cssSelector("[data-status=DONE]")).getText().contains("Browser acceptance task edited"));
                browser.findElement(By.linkText("Browser acceptance task edited")).click();
                wait.until(d -> !d.findElements(By.name("title")).isEmpty());
                repo.save(EDITOR,task.id(),3,fields("Teammate’s newer title",Status.DONE));
                browser.findElement(By.name("title")).sendKeys(Keys.END," local typing");
                click(browser,"Save changes");
                wait.until(d -> d.findElement(By.tagName("body")).getText().contains("Your changes were not saved"));
                assertTrue(browser.findElement(By.name("title")).getDomProperty("value").endsWith("local typing"));
                browser.get(base+"/app/?edit="+task.id());
                click(browser,"Archive task");
                wait.until(d -> d.getCurrentUrl().contains("archived=1"));
                assertTrue(repo.get(EDITOR,task.id()).archived());
                browser.findElement(By.linkText("Teammate’s newer title")).click();
                wait.until(d -> !d.findElements(By.xpath("//button[normalize-space(.)='Restore to board']")).isEmpty());
                click(browser,"Restore to board");
                wait.until(d -> !repo.get(EDITOR,task.id()).archived() && !d.getCurrentUrl().contains("edit="));
                browser.findElement(By.name("q")).sendKeys("Teammate"); click(browser,"Search");
                wait.until(d -> d.getCurrentUrl().contains("q=Teammate"));
                assertEquals(1,browser.findElements(By.cssSelector(".task-card")).size());
                browser.get(base+"/app/");
                browser.manage().window().setSize(new Dimension(390,844));
                browser.executeCdpCommand("Emulation.setDeviceMetricsOverride",Map.of("width",390,"height",844,"deviceScaleFactor",1,"mobile",false));
                screenshot(browser,"board-mobile");
                assertEquals(Boolean.TRUE,((JavascriptExecutor)browser).executeScript("return document.documentElement.scrollWidth <= innerWidth+1"));
                browser.get(base+"/app/?edit="+task.id());
                screenshot(browser,"editor-mobile");
                assertEquals(Boolean.TRUE,((JavascriptExecutor)browser).executeScript("return document.documentElement.scrollWidth <= innerWidth+1"));
            } catch(Throwable failure) { screenshot(browser,"failure"); throw failure; }
            finally { browser.quit(); }
        }
    }
    static void seed(TaskRepository repo) {
        String[][] tasks={{"Explore keyboard shortcuts","BACKLOG","LOW","Design"},{"Plan the next release","BACKLOG","MEDIUM","Kyle"},{"Tighten login error messages","READY","HIGH","Sam"},{"Add integration test fixtures","READY","MEDIUM","Kyle"},{"Review the database indexes","IN_PROGRESS","HIGH","Alex"},{"Build the task activity history","IN_PROGRESS","MEDIUM","Sam"},{"Ship the workspace navigation","DONE","LOW","Design"},{"Document local setup","DONE","MEDIUM","Kyle"}};
        for(var t:tasks) repo.create(EDITOR,UUID.randomUUID(),new Fields(t[0],"Define the next step and keep the team’s work moving.",Status.valueOf(t[1]),Priority.valueOf(t[2]),t[3],LocalDate.of(2026,9,18)));
    }
    static void click(WebDriver d,String text) { d.findElement(By.xpath("//button[normalize-space(.)='"+text+"']")).click(); }
    static void screenshot(WebDriver d,String name) throws Exception { Path path=Path.of("target","screenshots",name+".png"); Files.createDirectories(path.getParent()); Files.write(path,((TakesScreenshot)d).getScreenshotAs(OutputType.BYTES)); }
}
