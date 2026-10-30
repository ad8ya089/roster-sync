package com.moveinsync.rostersync;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.*;

class SecuredEndpointsTest {
    @Test void everyApiHandlerExceptTokenDeclaresPreAuthorize() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        int checked = 0;
        for (var candidate : scanner.findCandidateComponents("com.moveinsync.rostersync")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            var base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = base == null || base.value().length == 0 ? new String[]{""} : base.value();
            for (var method : org.springframework.util.ReflectionUtils.getUniqueDeclaredMethods(type)) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] suffixes = mapping.value().length == 0 ? new String[]{""} : mapping.value();
                for (String prefix : prefixes) for (String suffix : suffixes) {
                    String path = prefix + suffix;
                    if (!path.startsWith("/api/v1")) continue;
                    if (path.equals("/api/v1/auth/token") && Arrays.equals(mapping.method(), new RequestMethod[]{RequestMethod.POST})) continue;
                    assertThat(method.getDeclaredAnnotation(PreAuthorize.class)).as("@PreAuthorize on %s", method).isNotNull();
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(4);
    }
}
