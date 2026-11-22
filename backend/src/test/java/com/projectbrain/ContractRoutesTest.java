package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.projectbrain.support.PostgresTestBase;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest
@Disabled("Enabled in Task 8")
class ContractRoutesTest extends PostgresTestBase {

	private static final String BASE_PATH = "/api/v1";
	private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mappings;

	@Test
	void controllerRoutesMatchOpenApi() throws IOException {
		assertThat(controllerRoutes()).isEqualTo(contractRoutes());
	}

	private Set<String> controllerRoutes() {
		Set<String> routes = new TreeSet<>();
		mappings.getHandlerMethods().forEach((info, handler) -> {
			if (handler.getBeanType().getName().startsWith("com.projectbrain.")) {
				info.getMethodsCondition().getMethods().forEach(method -> info.getPatternValues()
					.forEach(pattern -> routes.add(method.name() + " " + pattern)));
			}
		});
		return routes;
	}

	@SuppressWarnings("unchecked")
	private Set<String> contractRoutes() throws IOException {
		Map<String, Object> contract;
		try (Reader reader = Files.newBufferedReader(Path.of("../docs/api/openapi.yaml"))) {
			contract = new Yaml().load(reader);
		}
		Set<String> routes = new TreeSet<>();
		((Map<String, Map<String, Object>>) contract.get("paths")).forEach((path, operations) -> operations.keySet()
			.stream()
			.filter(HTTP_METHODS::contains)
			.forEach(method -> routes.add(method.toUpperCase(Locale.ROOT) + " " + BASE_PATH + path)));
		return routes;
	}

}
