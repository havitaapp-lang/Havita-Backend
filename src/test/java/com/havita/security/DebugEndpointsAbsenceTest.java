package com.havita.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugEndpointsAbsenceTest {

    private static final Pattern DEBUG_PATTERN = Pattern.compile("(?i)(^|/)debug(/|$)");

    @Test
    void shouldNotExposeAnyDebugEndpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<BeanDefinition> candidates = scanner.findCandidateComponents("com.havita");
        List<String> violations = new ArrayList<>();

        for (BeanDefinition candidate : candidates) {
            Class<?> clazz = Class.forName(candidate.getBeanClassName());
            Set<String> classPaths = extractPaths(AnnotatedElementUtils.findMergedAnnotation(clazz, RequestMapping.class));

            for (Method method : clazz.getDeclaredMethods()) {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                Set<String> methodPaths = extractPaths(methodMapping);

                for (String cp : classPaths) {
                    for (String mp : methodPaths) {
                        String fullPath = combinePaths(cp, mp);
                        if (DEBUG_PATTERN.matcher(fullPath).find()) {
                            violations.add(String.format("Class: %s, Method: %s, Route: %s",
                                    clazz.getName(), method.getName(), fullPath));
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Debug endpoints found in controllers:\n" + String.join("\n", violations));
    }

    private Set<String> extractPaths(RequestMapping mapping) {
        if (mapping == null) {
            return Collections.singleton("");
        }
        Set<String> paths = new LinkedHashSet<>();
        if (mapping.path() != null) {
            for (String p : mapping.path()) {
                if (p != null && !p.isEmpty()) {
                    paths.add(p);
                }
            }
        }
        if (mapping.value() != null) {
            for (String p : mapping.value()) {
                if (p != null && !p.isEmpty()) {
                    paths.add(p);
                }
            }
        }
        return paths.isEmpty() ? Collections.singleton("") : paths;
    }

    private String combinePaths(String classPath, String methodPath) {
        String cp = (classPath == null) ? "" : classPath.trim();
        String mp = (methodPath == null) ? "" : methodPath.trim();

        if (cp.isEmpty() && mp.isEmpty()) {
            return "/";
        }
        if (cp.isEmpty()) {
            return mp.startsWith("/") ? mp : "/" + mp;
        }
        if (mp.isEmpty()) {
            return cp.startsWith("/") ? cp : "/" + cp;
        }

        if (!cp.startsWith("/")) {
            cp = "/" + cp;
        }
        if (cp.endsWith("/")) {
            cp = cp.substring(0, cp.length() - 1);
        }
        if (!mp.startsWith("/")) {
            mp = "/" + mp;
        }
        return cp + mp;
    }
}
