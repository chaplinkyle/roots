package dev.roots.processor;

import dev.roots.ApiRoute;
import dev.roots.Layout;
import dev.roots.Page;
import dev.roots.RoutePaths;
import dev.roots.StaticPathProvider;
import dev.roots.annotation.Authorize;
import dev.roots.annotation.Prerender;
import dev.roots.annotation.RootsApplication;
import dev.roots.annotation.Route;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates convention routes and writes deterministic, versioned route
 * manifests during Java compilation.
 */
public final class RootsRouteProcessor extends AbstractProcessor {
    private static final String ENABLE_ROOTS = "dev.roots.spring.boot.EnableRoots";
    private static final String HEADER = "ROOTS_ROUTE_MANIFEST";
    private static final String VERSION = "3";
    private final Map<String, TypeElement> sourceTypes = new LinkedHashMap<>();
    private final Map<String, TypeElement> applicationAnchors = new LinkedHashMap<>();
    private boolean generated;

    /** Creates a route processor. */
    public RootsRouteProcessor() {
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (var root : roundEnvironment.getRootElements()) {
            collectTypes(root);
        }
        sourceTypes.values().forEach(this::collectApplicationAnchor);
        if (roundEnvironment.processingOver() && !generated) {
            generated = true;
            applicationAnchors.values().forEach(this::generateManifest);
        }
        return false;
    }

    private void collectTypes(Element element) {
        if (element instanceof TypeElement type) {
            sourceTypes.put(processingEnv.getElementUtils().getBinaryName(type).toString(), type);
        }
        element.getEnclosedElements().forEach(this::collectTypes);
    }

    private void collectApplicationAnchor(TypeElement type) {
        for (var annotation : type.getAnnotationMirrors()) {
            var annotationName = annotation.getAnnotationType().toString();
            if (annotationName.equals(RootsApplication.class.getName())) {
                addAnchor(type);
            } else if (annotationName.equals(ENABLE_ROOTS)) {
                var value = annotationValue(annotation, "value");
                if (value instanceof TypeMirror mirror
                        && processingEnv.getTypeUtils().asElement(mirror) instanceof TypeElement anchor) {
                    addAnchor(anchor);
                }
            }
        }
    }

    private void addAnchor(TypeElement anchor) {
        applicationAnchors.put(
                processingEnv.getElementUtils().getBinaryName(anchor).toString(),
                anchor
        );
    }

    private void generateManifest(TypeElement anchor) {
        var anchorName = processingEnv.getElementUtils().getBinaryName(anchor).toString();
        var basePackage = packageName(anchor);
        if (basePackage.isBlank()) {
            error(anchor, "Roots application anchors must be declared in a named package");
            return;
        }
        var pagesPackage = basePackage + ".pages";
        var apiPackage = basePackage + ".api";
        var valid = new Flag();
        var layouts = discoverLayouts(pagesPackage, valid);
        var routes = new ArrayList<ManifestRoute>();

        for (var candidate : sourceTypes.values()) {
            var candidatePackage = packageName(candidate);
            if (candidatePackage.equals(pagesPackage)
                    && candidate.getSimpleName().contentEquals("ErrorPage")
                    && concrete(candidate, Page.class.getName())) {
                if (candidate.getAnnotation(Route.class) != null
                        || candidate.getAnnotation(Prerender.class) != null
                        || annotation(candidate, Authorize.class.getName()) != null) {
                    error(candidate, "The root ErrorPage convention cannot declare "
                            + "@Route, @Prerender, or @Authorize");
                    valid.fail();
                }
                if (routes.stream().anyMatch(route -> route.kind().equals("ERROR_PAGE"))) {
                    error(candidate, "Multiple concrete ErrorPage classes found under " + pagesPackage);
                    valid.fail();
                }
                routes.add(new ManifestRoute("ERROR_PAGE", "/", binaryName(candidate),
                        List.of(), candidate));
            } else if (candidatePackage.equals(pagesPackage)
                    && candidate.getSimpleName().contentEquals("NotFound")
                    && concrete(candidate, Page.class.getName())) {
                if (candidate.getAnnotation(Route.class) != null) {
                    error(candidate, "The root NotFound page convention cannot declare @Route");
                    valid.fail();
                }
                if (candidate.getAnnotation(Prerender.class) != null) {
                    error(candidate, "The root NotFound page convention cannot declare @Prerender");
                    valid.fail();
                }
                if (routes.stream().anyMatch(route -> route.kind().equals("NOT_FOUND"))) {
                    error(candidate, "Multiple concrete NotFound pages found under " + pagesPackage);
                    valid.fail();
                }
                validateAuthorization(candidate, valid);
                var chain = layoutChain(candidatePackage, pagesPackage, layouts);
                chain.forEach(layout -> validateAuthorization(layout, valid));
                routes.add(new ManifestRoute("NOT_FOUND", "/", binaryName(candidate),
                        chain.stream().map(this::binaryName).toList(), candidate));
            } else if (beneath(candidatePackage, pagesPackage)
                    && candidate.getSimpleName().contentEquals("Page")
                    && concrete(candidate, Page.class.getName())) {
                var path = routePath(candidate, pagesPackage, "", valid);
                validateAuthorization(candidate, valid);
                var chain = layoutChain(candidatePackage, pagesPackage, layouts);
                chain.forEach(layout -> validateAuthorization(layout, valid));
                if (path != null) {
                    validatePrerender(candidate, chain, path, valid);
                    routes.add(new ManifestRoute("PAGE", path, binaryName(candidate),
                            chain.stream().map(this::binaryName).toList(), candidate));
                }
            } else if (beneath(candidatePackage, apiPackage)
                    && candidate.getSimpleName().contentEquals("Route")
                    && concrete(candidate, ApiRoute.class.getName())) {
                var path = routePath(candidate, apiPackage, "/api", valid);
                validateAuthorization(candidate, valid);
                if (path != null) {
                    routes.add(new ManifestRoute("API", path, binaryName(candidate), List.of(), candidate));
                }
            }
        }

        if (routes.stream().noneMatch(route -> route.kind().equals("PAGE"))) {
            error(anchor, "No concrete Page classes found under " + pagesPackage);
            valid.fail();
        }
        validateDuplicates(routes, valid);
        if (valid.failed()) {
            return;
        }
        routes.sort(Comparator.comparing(ManifestRoute::kind)
                .thenComparing(ManifestRoute::path)
                .thenComparing(ManifestRoute::className));
        writeManifest(anchor, anchorName, pagesPackage, apiPackage, routes);
    }

    private Map<String, TypeElement> discoverLayouts(String pagesPackage, Flag valid) {
        var layouts = new LinkedHashMap<String, TypeElement>();
        for (var candidate : sourceTypes.values()) {
            var candidatePackage = packageName(candidate);
            if (beneath(candidatePackage, pagesPackage)
                    && candidate.getSimpleName().contentEquals("Layout")
                    && concrete(candidate, Layout.class.getName())) {
                var previous = layouts.putIfAbsent(candidatePackage, candidate);
                if (previous != null) {
                    error(candidate, "Multiple concrete Layout classes in package " + candidatePackage
                            + ": " + binaryName(previous) + " and " + binaryName(candidate));
                    valid.fail();
                }
            }
        }
        return layouts;
    }

    private List<TypeElement> layoutChain(
            String pagePackage,
            String pagesPackage,
            Map<String, TypeElement> layouts
    ) {
        var result = new ArrayList<TypeElement>();
        var current = pagesPackage;
        if (layouts.containsKey(current)) {
            result.add(layouts.get(current));
        }
        if (!pagePackage.equals(pagesPackage)) {
            var relative = pagePackage.substring(pagesPackage.length() + 1);
            for (var segment : relative.split("\\.")) {
                current += "." + segment;
                if (layouts.containsKey(current)) {
                    result.add(layouts.get(current));
                }
            }
        }
        return List.copyOf(result);
    }

    private String routePath(TypeElement candidate, String basePackage, String prefix, Flag valid) {
        try {
            var route = annotation(candidate, Route.class.getName());
            return route == null
                    ? RoutePaths.fromPackage(packageName(candidate), basePackage, prefix)
                    : RoutePaths.fromTemplate((String) annotationValue(route, "value"));
        } catch (IllegalArgumentException exception) {
            error(candidate, exception.getMessage());
            valid.fail();
            return null;
        }
    }

    private void validateAuthorization(TypeElement candidate, Flag valid) {
        var authorization = annotation(candidate, Authorize.class.getName());
        if (authorization == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        var values = (List<? extends AnnotationValue>) annotationValue(authorization, "value");
        if (values.isEmpty()) {
            error(candidate, "@Authorize must name at least one policy: " + binaryName(candidate));
            valid.fail();
        }
        for (var value : values) {
            var policy = (String) value.getValue();
            if (!policy.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
                error(candidate, "Invalid authorization policy on " + binaryName(candidate) + ": " + policy);
                valid.fail();
            }
        }
    }

    private void validatePrerender(
            TypeElement page,
            List<TypeElement> layouts,
            String path,
            Flag valid
    ) {
        var prerender = annotation(page, Prerender.class.getName());
        if (prerender == null) {
            return;
        }
        var interval = (Long) annotationValue(prerender, "revalidateSeconds");
        if (interval < 0) {
            error(page, "@Prerender revalidateSeconds cannot be negative: " + interval);
            valid.fail();
        }
        if (path.equals("/_roots") || path.startsWith("/_roots/")) {
            error(page, "@Prerender cannot use the reserved /_roots framework path: " + path);
            valid.fail();
        }
        if (annotation(page, Authorize.class.getName()) != null
                || layouts.stream().anyMatch(layout -> annotation(layout, Authorize.class.getName()) != null)) {
            error(page, "@Prerender pages cannot have page or layout authorization policies: " + path);
            valid.fail();
        }
        var provider = (TypeMirror) annotationValue(prerender, "paths");
        var sentinel = StaticPathProvider.None.class.getCanonicalName();
        var hasProvider = provider != null && !provider.toString().equals(sentinel);
        var dynamic = path.indexOf('{') >= 0;
        if (dynamic && !hasProvider) {
            error(page, "Dynamic @Prerender route requires a StaticPathProvider: " + path);
            valid.fail();
        } else if (!dynamic && hasProvider) {
            error(page, "Static @Prerender route cannot declare a StaticPathProvider: " + path);
            valid.fail();
        }
    }

    private void validateDuplicates(List<ManifestRoute> routes, Flag valid) {
        var seen = new LinkedHashMap<String, ManifestRoute>();
        for (var route : routes) {
            var key = route.kind() + "\t" + RoutePaths.shape(route.path());
            var previous = seen.putIfAbsent(key, route);
            if (previous != null) {
                error(route.element(), "Duplicate " + (route.kind().equals("PAGE") ? "page" : "API route")
                        + " shape " + RoutePaths.shape(route.path()) + ": " + previous.className()
                        + " (" + previous.path() + ") and " + route.className() + " (" + route.path() + ")");
                valid.fail();
            }
        }
    }

    private void writeManifest(
            TypeElement anchor,
            String anchorName,
            String pagesPackage,
            String apiPackage,
            List<ManifestRoute> routes
    ) {
        var resource = "META-INF/roots/routes/" + anchorName + ".routes";
        try {
            var file = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    resource,
                    originatingElements(anchor, routes)
            );
            try (var writer = file.openWriter()) {
                writer.write(HEADER + "\t" + VERSION + "\t" + anchorName + "\t"
                        + pagesPackage + "\t" + apiPackage + "\n");
                for (var route : routes) {
                    writer.write(route.kind() + "\t" + route.path() + "\t" + route.className()
                            + "\t" + String.join(",", route.layouts()) + "\n");
                }
            }
        } catch (IOException exception) {
            error(anchor, "Could not write Roots route manifest " + resource + ": " + exception.getMessage());
        }
    }

    private Element[] originatingElements(TypeElement anchor, List<ManifestRoute> routes) {
        var elements = new LinkedHashSet<Element>();
        elements.add(anchor);
        routes.forEach(route -> elements.add(route.element()));
        return elements.toArray(Element[]::new);
    }

    private boolean concrete(TypeElement candidate, String contractName) {
        if (candidate.getModifiers().contains(Modifier.ABSTRACT)) {
            return false;
        }
        var contract = processingEnv.getElementUtils().getTypeElement(contractName);
        return contract != null && processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(candidate.asType()),
                processingEnv.getTypeUtils().erasure(contract.asType())
        );
    }

    private AnnotationMirror annotation(TypeElement type, String name) {
        return type.getAnnotationMirrors().stream()
                .filter(candidate -> candidate.getAnnotationType().toString().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Object annotationValue(AnnotationMirror annotation, String member) {
        return processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals(member))
                .map(Map.Entry::getValue)
                .map(AnnotationValue::getValue)
                .findFirst()
                .orElse(null);
    }

    private boolean beneath(String packageName, String basePackage) {
        return packageName.equals(basePackage) || packageName.startsWith(basePackage + ".");
    }

    private String packageName(TypeElement type) {
        PackageElement owner = processingEnv.getElementUtils().getPackageOf(type);
        return owner.getQualifiedName().toString();
    }

    private String binaryName(TypeElement type) {
        return processingEnv.getElementUtils().getBinaryName(type).toString();
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record ManifestRoute(
            String kind,
            String path,
            String className,
            List<String> layouts,
            TypeElement element
    ) {
    }

    private static final class Flag {
        private boolean failed;

        void fail() {
            failed = true;
        }

        boolean failed() {
            return failed;
        }
    }
}
