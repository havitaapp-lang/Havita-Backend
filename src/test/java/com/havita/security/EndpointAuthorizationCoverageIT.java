package com.havita.security;

import com.havita.support.PostgresIntegrationTest;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-9 (P2): prueba preventiva de cobertura de autorizacion. Falla si aparece un endpoint sin clasificar, si cambia la
 * politica de uno existente sin revisar la linea base, o si un endpoint administrativo queda sin proteccion.
 *
 * <h2>Que inspecciona</h2>
 * <ul>
 *   <li><b>Mappings reales de Spring MVC</b>: todos los {@link RequestMappingInfoHandlerMapping} del contexto
 *       (controladores de la aplicacion, Actuator y springdoc).</li>
 *   <li><b>Reglas globales de Spring Security</b>: se evalua el {@code AuthorizationManager} del
 *       {@link AuthorizationFilter} real con un usuario anonimo, sin ejecutar el controlador (los endpoints publicos
 *       que envian correos no se disparan). Un endpoint es PUBLIC si la cadena admite al anonimo.</li>
 *   <li><b>Anotaciones</b> {@code @PreAuthorize}, {@code @PostAuthorize}, {@code @Secured} y {@code @RolesAllowed}, a
 *       nivel de metodo y de clase (metodo primero, como Spring Security).</li>
 * </ul>
 *
 * <h2>Linea base</h2>
 * {@code src/test/resources/security/endpoint-authorization-baseline.tsv}: una fila por metodo HTTP y ruta, con
 * <ol>
 *   <li>{@code ACCESS}: {@code PUBLIC}, {@code AUTHENTICATED} (sin anotacion: cualquier usuario autenticado) o la
 *       expresion de autorizacion normalizada;</li>
 *   <li>{@code RESOURCE}: evidencia de autorizacion <b>efectiva</b> sobre el recurso, distinta de la cobertura
 *       declarativa (ver {@link Resource});</li>
 *   <li>{@code JUSTIFICATION}: obligatoria para PUBLIC, AUTHENTICATED, SELF y PUBLIC_TOKEN.</li>
 * </ol>
 *
 * <h2>Cobertura declarativa frente a autorizacion efectiva</h2>
 * Un {@code @PreAuthorize("hasAnyRole(...)")} solo dice <i>quien</i> puede llamar, no <i>sobre que</i> comunidad o
 * recurso. Por eso la columna RESOURCE se verifica por separado: GLOBAL_ONLY exige {@code SCOPE_GLOBAL} en la
 * expresion; EXPR exige una comprobacion de recurso en la propia expresion ({@code @bean.metodo(#param)});
 * {@code IT:Clase} exige que esa prueba de seguridad exista y llame a esta ruta. UNVERIFIED (ruta con identificador y
 * solo cobertura declarativa) se permite pero con un tope ({@link #MAX_UNVERIFIED}) que solo puede bajar.
 *
 * Para regenerar una propuesta de linea base (que luego se revisa a mano):
 * {@code ENDPOINT_BASELINE_WRITE=true ./gradlew test --tests '*EndpointAuthorizationCoverageIT'}; se escribe en
 * {@code build/reports/endpoint-authorization/proposed-baseline.tsv}.
 */
class EndpointAuthorizationCoverageIT extends PostgresIntegrationTest {

    private static final String BASELINE = "/security/endpoint-authorization-baseline.tsv";
    private static final Path REPORT_DIR = Paths.get("build", "reports", "endpoint-authorization");

    /**
     * Tope de rutas con identificador cuya autorizacion sobre el recurso no esta probada automaticamente. Solo puede
     * bajar: anadir una prueba IT y reclasificar la fila, y luego reducir este numero.
     */
    static final int MAX_UNVERIFIED = 2;

    /** Pruebas de integracion cuyo objeto es el aislamiento entre comunidades, usuarios o unidades. */
    static final Set<String> SECURITY_ITS = Set.of(
            "AdminAuthorizationIT", "AccessRevocationIT", "AuthorizationP2IT", "UnitScopedEndpointsIT",
            "UnitScopedResourcesIT", "PropertyTransferSecurityIT", "InvitationTokenSecurityIT",
            "ResidentInvitationRegenerateIT", "ResidentTenantIsolationIT", "ResidentPrivacyIT", "ResidentRemovalIT",
            "ResidentReactivationIT", "ResidentSelfVehiclePetDeleteIT", "PqrsPrivacyIT", "AssemblyPrivacyIT",
            "CommunicationPrivacyIT", "SseIsolationIT", "SseEventPolicyIT", "FileAccessSecurityIT",
            "SignedFileUrlSecurityIT", "UnverifiedEndpointsIsolationIT", "StaffRecordLinkingIT",
            "ResidentScopedExpressionsIT", "SingleRoleIT", "PollVotingIT", "AssemblyConsolidationIT", "AssemblyRepresentationIT",
            // P8-A3: /intercom/calls/{id}/prepare (residente llamado frente a otro residente, porteria y otra comunidad).
            "IntercomEarlyTokenIT",
            // P8-B: /intercom/calls/incoming (residente llamado frente a otro residente, porteria y otra comunidad).
            "IntercomPushIT",
            // Fase 3: chat interno (residente propio frente a otro residente, porteria de otra comunidad, responsable).
            "ChatIT",
            // /staff/{id}/status: administracion propia frente a otra comunidad, porteria y residentes; revoca el acceso.
            "StaffStatusAccessIT",
            // /staff/{id} y /staff/{id}/access: escalada de rol, cuentas compartidas, otra comunidad, porteria y residentes.
            "StaffAccessManagementIT",
            // /access/visitors/{id} (PUT): administracion y porteria propias frente a otra comunidad y residentes.
            "VisitorStaffEditIT");

    /**
     * Ambitos administrativos: un endpoint de estas rutas nunca puede quedar PUBLIC ni AUTHENTICATED sin expresion,
     * aunque la linea base lo diga (regla dura, sin excepciones).
     */
    static final Pattern ADMIN_PATH = Pattern.compile(
            "^/api/v1/(tenants|memberships|users(?!/me(/|$))|roles|audit|subscriptions|feature-flags|organizations|admin|accounting|platform)(/.*)?$");

    /**
     * Unicas excepciones a la regla dura anterior, cada una con su motivo y una prueba de autorizacion efectiva.
     */
    static final Map<String, String> ADMIN_PATH_EXCEPTIONS = Map.of(
            "GET /api/v1/roles/assignable",
            "Catalogo de roles asignables: el metodo decide por el rol efectivo y devuelve una lista vacia a quien no es "
                    + "administracion (AuthorizationP2IT.assignableRolesAreEmptyForNonAdministrators)");

    /** Roles con capacidad administrativa u operativa sobre la comunidad o la plataforma. */
    static final Set<String> ADMIN_ROLES = Set.of("SUPER_ADMIN", "PLATFORM_ADMIN", "READ_ONLY", "COMMUNITY_ADMIN", "ACCOUNTANT");

    enum Resource {
        /** Sin identificador en la ruta (colecciones de la comunidad actual o acciones sin recurso). */
        NONE,
        /** La expresion exige SCOPE_GLOBAL: solo la administracion global, sin aislamiento por comunidad que probar. */
        GLOBAL_ONLY,
        /**
         * La expresion incluye una comprobacion del recurso concreto (por ejemplo @accessValidator...(#id)). Cobertura
         * PARCIAL: en las expresiones actuales esa comprobacion esta en la rama de RESIDENT; para personal y
         * administracion el aislamiento de comunidad depende del filtro por tenant del servicio (no probado aqui).
         */
        EXPR,
        /** Opera solo sobre la identidad del llamante (su perfil, sus sesiones). Requiere justificacion. */
        SELF,
        /** Endpoint publico autorizado por un secreto de un solo uso en la ruta. Requiere justificacion. */
        PUBLIC_TOKEN,
        /** Hay una prueba de seguridad (IT:Clase) que llama a esta ruta. */
        IT,
        /** Ruta con identificador protegida solo declarativamente; aislamiento no probado automaticamente. */
        UNVERIFIED,
        /** Endpoint del framework (Actuator, springdoc, /error), no de la aplicacion. Requiere justificacion. */
        FRAMEWORK
    }

    record Endpoint(String method, String path, String handler, String access, boolean anonymousAllowed, boolean framework) {
        String key() {
            return method + " " + path;
        }

        boolean hasPathVariable() {
            return path.contains("{");
        }
    }

    record BaselineRow(String method, String path, String access, String resource, String justification) {
        String key() {
            return method + " " + path;
        }
    }

    @Autowired private ApplicationContext context;
    @Autowired private FilterChainProxy filterChainProxy;

    private static Map<String, String> testSources;

    @BeforeAll
    static void indexTestSources() throws IOException {
        testSources = new TreeMap<>();
        Path root = Paths.get("src", "test", "java");
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : files.filter(f -> f.toString().endsWith("IT.java")).toList()) {
                    String name = p.getFileName().toString().replace(".java", "");
                    testSources.put(name, Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
    }

    // ── Inventario ─────────────────────────────────────────────────────────────

    private List<Endpoint> inventory() {
        AuthorizationManager<HttpServletRequest> manager = httpAuthorizationManager();
        Map<String, Endpoint> byKey = new TreeMap<>();
        for (RequestMappingInfoHandlerMapping mapping : context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = e.getKey();
                HandlerMethod hm = e.getValue();
                List<String> methods = info.getMethodsCondition().getMethods().isEmpty()
                        ? List.of("ANY")
                        : info.getMethodsCondition().getMethods().stream().map(Enum::name).sorted().toList();
                for (String pattern : info.getPatternValues()) {
                    for (String method : methods) {
                        String access = declaredExpression(hm).orElse(null);
                        boolean anonymous = anonymousAllowed(manager, "ANY".equals(method) ? "GET" : method, pattern);
                        String accessLabel = access != null ? access : (anonymous ? "PUBLIC" : "AUTHENTICATED");
                        Endpoint ep = new Endpoint(method, pattern,
                                hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName(), accessLabel, anonymous,
                                !hm.getBeanType().getName().startsWith("com.havita."));
                        byKey.put(ep.key(), ep);
                    }
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** Expresion de autorizacion del metodo o, si no tiene, de la clase (misma precedencia que Spring Security). */
    static Optional<String> declaredExpression(HandlerMethod hm) {
        Optional<String> onMethod = expressionOn(hm.getMethod());
        return onMethod.isPresent() ? onMethod : expressionOn(hm.getBeanType());
    }

    private static Optional<String> expressionOn(AnnotatedElement element) {
        List<String> parts = new ArrayList<>();
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
        if (pre != null) parts.add("pre:" + normalize(pre.value()));
        PostAuthorize post = AnnotatedElementUtils.findMergedAnnotation(element, PostAuthorize.class);
        if (post != null) parts.add("post:" + normalize(post.value()));
        Secured secured = AnnotatedElementUtils.findMergedAnnotation(element, Secured.class);
        if (secured != null) parts.add("secured:" + String.join(",", secured.value()));
        RolesAllowed roles = AnnotatedElementUtils.findMergedAnnotation(element, RolesAllowed.class);
        if (roles != null) parts.add("roles:" + String.join(",", roles.value()));
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(" ; ", parts));
    }

    private static String normalize(String expr) {
        return expr.replaceAll("\\s+", " ").replace("\"", "'").trim();
    }

    @SuppressWarnings("unchecked")
    private AuthorizationManager<HttpServletRequest> httpAuthorizationManager() {
        for (SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
            for (Filter f : chain.getFilters()) {
                if (f instanceof AuthorizationFilter af) {
                    return af.getAuthorizationManager();
                }
            }
        }
        throw new IllegalStateException("No hay AuthorizationFilter en la cadena de Spring Security");
    }

    /** Evalua las reglas HTTP globales para un anonimo, sin ejecutar filtros posteriores ni el controlador. */
    private boolean anonymousAllowed(AuthorizationManager<HttpServletRequest> manager, String method, String pattern) {
        String uri = pattern.replaceAll("\\{[^}]+}", "00000000-0000-0000-0000-000000000000").replace("/**", "/x").replace("*", "x");
        MockHttpServletRequest req = new MockHttpServletRequest(context.getBean(jakarta.servlet.ServletContext.class), method, uri);
        req.setServletPath("");
        req.setPathInfo(uri);
        var anonymous = new AnonymousAuthenticationToken("coverage", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        // Solo la cadena que corresponde a la peticion decide (hoy hay una).
        for (SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
            if (chain.matches(req)) {
                AuthorizationDecision d = manager.check(() -> anonymous, req);
                return d != null && d.isGranted();
            }
        }
        return false;
    }

    // ── Linea base ─────────────────────────────────────────────────────────────

    private static List<BaselineRow> baseline() throws IOException {
        try (InputStream in = EndpointAuthorizationCoverageIT.class.getResourceAsStream(BASELINE)) {
            if (in == null) return List.of();
            List<BaselineRow> rows = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                if (c.length != 5) throw new IllegalStateException("Fila de linea base con " + c.length + " columnas: " + line);
                rows.add(new BaselineRow(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim()));
            }
            return rows;
        }
    }

    private static Resource resourceKind(String value) {
        return value.startsWith("IT:") ? Resource.IT : Resource.valueOf(value);
    }

    private static final Pattern MOCKMVC_CALL =
            Pattern.compile("\\b(get|post|put|patch|delete)\\(\\s*\"(/api/v1/[^\"\\s]*)\"");

    /**
     * true si la prueba hace una llamada MockMvc con ese metodo HTTP a esa ruta (variables y %s normalizados a {}).
     * Conservador: una ruta construida por concatenacion o pasada a una utilidad sin metodo no cuenta como evidencia.
     */
    static boolean testCallsPath(String source, String method, String path) {
        String target = normalizePath(path);
        Matcher m = MOCKMVC_CALL.matcher(source);
        while (m.find()) {
            if (!"ANY".equals(method) && !m.group(1).equalsIgnoreCase(method)) continue;
            String literal = m.group(2);
            int q = literal.indexOf('?');
            if (q >= 0) literal = literal.substring(0, q);
            if (normalizePath(literal).equals(target)) return true;
        }
        return false;
    }

    private static String normalizePath(String p) {
        return p.replaceAll("\\{[^}]*}", "{}").replace("%s", "{}").replaceAll("/+$", "");
    }

    /** Clasificacion propuesta (para generar la linea base; nunca sustituye la revision humana). */
    private static String proposedResource(Endpoint ep) {
        if (ep.framework()) return Resource.FRAMEWORK.name();
        if (ep.access().contains("SCOPE_GLOBAL")) return Resource.GLOBAL_ONLY.name();
        if (ep.access().matches(".*@[A-Za-z]+\\.[A-Za-z]+\\(#.*")) return Resource.EXPR.name();
        if (!ep.hasPathVariable()) return Resource.NONE.name();
        for (String it : SECURITY_ITS) {
            String src = testSources.get(it);
            if (src != null && testCallsPath(src, ep.method(), ep.path())) return "IT:" + it;
        }
        return Resource.UNVERIFIED.name();
    }

    // ── Prueba ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SEC-9 P2: todo endpoint esta clasificado, su politica coincide con la linea base y ningun endpoint administrativo queda sin proteccion")
    void everyEndpointHasAnExplicitAuthorizationPolicy() throws IOException {
        List<Endpoint> endpoints = inventory();
        assertThat(endpoints).as("inventario de mappings").hasSizeGreaterThan(100);
        writeReports(endpoints);

        Map<String, BaselineRow> base = new LinkedHashMap<>();
        for (BaselineRow r : baseline()) {
            assertThat(base.put(r.key(), r)).as("fila duplicada en la linea base: " + r.key()).isNull();
        }
        Map<String, Endpoint> current = endpoints.stream().collect(Collectors.toMap(Endpoint::key, e -> e, (x, y) -> x, LinkedHashMap::new));

        List<String> problems = new ArrayList<>();

        // 1. Nuevos endpoints sin clasificar, politicas cambiadas y filas obsoletas.
        for (Endpoint ep : endpoints) {
            BaselineRow row = base.get(ep.key());
            if (row == null) {
                problems.add("NUEVO sin clasificar: " + ep.key() + " (" + ep.handler() + ") access=" + ep.access());
            } else if (!row.access().equals(ep.access())) {
                problems.add("POLITICA CAMBIADA: " + ep.key() + " linea base=" + row.access() + " actual=" + ep.access());
            }
        }
        for (BaselineRow row : base.values()) {
            if (!current.containsKey(row.key())) problems.add("OBSOLETO en la linea base (ya no existe): " + row.key());
        }

        // 2. Reglas duras: administracion nunca publica ni sin expresion; expresiones administrativas nunca publicas.
        for (Endpoint ep : endpoints) {
            boolean undeclared = "PUBLIC".equals(ep.access()) || "AUTHENTICATED".equals(ep.access());
            if (undeclared && ADMIN_PATH.matcher(ep.path()).matches() && !ADMIN_PATH_EXCEPTIONS.containsKey(ep.key())) {
                problems.add("ADMINISTRATIVO SIN PROTECCION: " + ep.key() + " (" + ep.handler() + ") access=" + ep.access());
            }
            if (ep.anonymousAllowed() && ADMIN_ROLES.stream().anyMatch(ep.access()::contains)) {
                // La expresion de metodo sigue aplicando, pero el endpoint queda fuera de la regla global de autenticacion.
                problems.add("ADMINISTRATIVO EXPUESTO EN permitAll: " + ep.key() + " access=" + ep.access());
            }
        }

        // 3. Justificaciones y evidencias de autorizacion efectiva.
        int unverified = 0;
        for (BaselineRow row : base.values()) {
            Endpoint ep = current.get(row.key());
            if (ep == null) continue;
            Resource kind;
            try {
                kind = resourceKind(row.resource());
            } catch (IllegalArgumentException ex) {
                problems.add("RESOURCE desconocido '" + row.resource() + "': " + row.key());
                continue;
            }
            boolean needsJustification = "PUBLIC".equals(row.access()) || "AUTHENTICATED".equals(row.access())
                    || kind == Resource.SELF || kind == Resource.PUBLIC_TOKEN || kind == Resource.FRAMEWORK;
            if (needsJustification && row.justification().length() < 15) {
                problems.add("SIN JUSTIFICACION: " + row.key() + " (" + row.access() + ", " + row.resource() + ")");
            }
            switch (kind) {
                case GLOBAL_ONLY -> {
                    if (!ep.access().contains("SCOPE_GLOBAL")) problems.add("GLOBAL_ONLY sin SCOPE_GLOBAL: " + row.key());
                }
                case EXPR -> {
                    if (!ep.access().matches(".*@[A-Za-z]+\\.[A-Za-z]+\\(#.*")) problems.add("EXPR sin comprobacion de recurso en la expresion: " + row.key());
                }
                case IT -> {
                    String cls = row.resource().substring(3);
                    String src = testSources.get(cls);
                    if (!SECURITY_ITS.contains(cls) || src == null) {
                        problems.add("IT no reconocida como prueba de seguridad: " + row.resource() + " en " + row.key());
                    } else if (!testCallsPath(src, ep.method(), ep.path())) {
                        problems.add("IT sin evidencia: " + cls + " no llama a " + ep.key());
                    }
                }
                case NONE -> {
                    if (ep.hasPathVariable()) problems.add("NONE en una ruta con identificador: " + row.key());
                }
                case UNVERIFIED -> unverified++;
                case FRAMEWORK -> {
                    if (!ep.framework()) problems.add("FRAMEWORK en un controlador de la aplicacion: " + row.key());
                }
                case SELF, PUBLIC_TOKEN -> { }
            }
            if ("PUBLIC".equals(ep.access()) && ep.hasPathVariable() && kind != Resource.PUBLIC_TOKEN && kind != Resource.FRAMEWORK) {
                problems.add("PUBLIC con identificador debe ser PUBLIC_TOKEN: " + row.key());
            }
        }
        if (unverified > MAX_UNVERIFIED) {
            problems.add("UNVERIFIED " + unverified + " > tope " + MAX_UNVERIFIED + ": un endpoint nuevo con identificador necesita "
                    + "una prueba de aislamiento (IT:...) o una comprobacion de recurso en la expresion");
        }

        printSummary(endpoints, base, unverified);
        assertThat(problems).as("Cobertura de autorizacion (ver build/reports/endpoint-authorization/)").isEmpty();
        assertThat(unverified).as("el tope MAX_UNVERIFIED debe bajar cuando se verifican endpoints").isEqualTo(MAX_UNVERIFIED);
    }

    @Test
    @DisplayName("SEC-9 P2: la deteccion distingue PUBLIC, AUTHENTICATED y expresiones, y la evidencia IT exige llamar a la ruta")
    void detectorSelfCheck() {
        List<Endpoint> endpoints = inventory();
        Map<String, Endpoint> byKey = endpoints.stream().collect(Collectors.toMap(Endpoint::key, e -> e, (x, y) -> x));
        // Reglas globales: el login es publico por permitAll; un endpoint sin anotacion exige autenticacion.
        assertThat(byKey.get("POST /api/v1/auth/login").access()).isEqualTo("PUBLIC");
        assertThat(byKey.get("POST /api/v1/auth/login").anonymousAllowed()).isTrue();
        // Anotacion de metodo: los endpoints globales exigen SCOPE_GLOBAL; ningun admin sale como PUBLIC.
        assertThat(endpoints.stream().filter(e -> e.access().contains("SCOPE_GLOBAL")).count()).isGreaterThanOrEqualTo(27);
        assertThat(endpoints.stream().filter(e -> e.access().contains("COMMUNITY_ADMIN")).noneMatch(Endpoint::anonymousAllowed)).isTrue();
        // La evidencia IT compara rutas normalizadas, no prefijos.
        assertThat(testCallsPath("put(\"/api/v1/properties/{id}\", x)", "PUT", "/api/v1/properties/{propertyId}")).isTrue();
        assertThat(testCallsPath("put(\"/api/v1/properties/{id}\", x)", "DELETE", "/api/v1/properties/{id}")).isFalse();
        assertThat(testCallsPath("put(\"/api/v1/properties/{id}\", x)", "PUT", "/api/v1/properties/{id}/owner")).isFalse();
        assertThat(testCallsPath("get(\"/api/v1/tenants/%s\".formatted(b))", "GET", "/api/v1/tenants/{id}")).isTrue();
    }

    // ── Informes ───────────────────────────────────────────────────────────────

    private void writeReports(List<Endpoint> endpoints) throws IOException {
        Files.createDirectories(REPORT_DIR);
        StringBuilder inv = new StringBuilder("METHOD\tPATH\tHANDLER\tACCESS\tANONYMOUS\tPROPOSED_RESOURCE\n");
        StringBuilder proposed = new StringBuilder();
        for (Endpoint ep : endpoints) {
            String res = proposedResource(ep);
            inv.append(String.join("\t", ep.method(), ep.path(), ep.handler(), ep.access(), String.valueOf(ep.anonymousAllowed()), res)).append('\n');
            proposed.append(String.join("\t", ep.method(), ep.path(), ep.access(), res, "")).append('\n');
        }
        Files.writeString(REPORT_DIR.resolve("inventory.tsv"), inv, StandardCharsets.UTF_8);
        if ("true".equalsIgnoreCase(System.getenv("ENDPOINT_BASELINE_WRITE"))) {
            Files.writeString(REPORT_DIR.resolve("proposed-baseline.tsv"), proposed, StandardCharsets.UTF_8);
        }
    }

    private static void printSummary(List<Endpoint> endpoints, Map<String, BaselineRow> base, int unverified) {
        Map<String, Long> byAccess = endpoints.stream().collect(Collectors.groupingBy(
                e -> e.access().equals("PUBLIC") || e.access().equals("AUTHENTICATED") ? e.access() : "EXPRESSION",
                TreeMap::new, Collectors.counting()));
        Map<String, Long> byResource = base.values().stream().collect(Collectors.groupingBy(
                r -> r.resource().startsWith("IT:") ? "IT" : r.resource(), TreeMap::new, Collectors.counting()));
        System.out.println("[endpoint-authorization] endpoints=" + endpoints.size() + " access=" + byAccess
                + " resource=" + byResource + " unverified=" + unverified + "/" + MAX_UNVERIFIED);
    }
}
