# ${artifactId}

Generated with Roots. Requires JDK 26 and Maven.

```shell
mvn package
java -jar target/${artifactId}-${version}.jar
```

Add routes beneath `src/main/java/${packageInPathFormat}/pages` using `Page.java` and `Layout.java`.
Reusable UI implements `Component`; browser events call methods annotated with `@ServerAction`.
