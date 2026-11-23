package com.projectbrain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.Set;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

@AnalyzeClasses(packages = "com.projectbrain", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	private static final String ROOT = "com.projectbrain.";
	private static final Set<String> MODULES = Set.of("identity", "organization", "project", "task", "audit", "team");

	@ArchTest
	static final ArchRule modulesOnlyUseOtherModulesApi = classes()
		.that().resideInAPackage("com.projectbrain..")
		.should(onlyUseOtherModulesThroughApi())
		.allowEmptyShould(true);

	private static ArchCondition<JavaClass> onlyUseOtherModulesThroughApi() {
		return new ArchCondition<>("use other modules only through their api package") {
			@Override
			public void check(JavaClass origin, ConditionEvents events) {
				String originModule = moduleOf(origin.getPackageName());
				if (originModule == null) {
					return;
				}
				for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
					String targetPackage = dependency.getTargetClass().getPackageName();
					String targetModule = moduleOf(targetPackage);
					if (targetModule == null || targetModule.equals(originModule)) {
						continue;
					}
					String api = ROOT + targetModule + ".api";
					if (!targetPackage.equals(api) && !targetPackage.startsWith(api + ".")) {
						events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
					}
				}
			}
		};
	}

	private static String moduleOf(String packageName) {
		if (!packageName.startsWith(ROOT)) {
			return null;
		}
		String module = packageName.substring(ROOT.length()).split("\\.")[0];
		return MODULES.contains(module) ? module : null;
	}

}
