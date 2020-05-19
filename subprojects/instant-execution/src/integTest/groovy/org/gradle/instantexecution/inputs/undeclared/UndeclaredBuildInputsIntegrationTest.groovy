/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.instantexecution.inputs.undeclared


import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.instantexecution.AbstractInstantExecutionIntegrationTest
import spock.lang.Issue
import spock.lang.Unroll

class UndeclaredBuildInputsIntegrationTest extends AbstractInstantExecutionIntegrationTest {
    @Unroll
    def "reports build logic reading a system property set #mechanism.description via the Java API"() {
        buildFile << """
            // not declared
            System.getProperty("CI")
        """

        when:
        mechanism.setup(this)
        instantFails(*mechanism.gradleArgs)

        then:
        // TODO - use problems fixture, however build script class is generated
        failure.assertThatDescription(containsNormalizedString("- unknown location: read system property 'CI' from class 'build_"))
        failure.assertHasFileName("Build file '${buildFile.absolutePath}'")
        failure.assertHasLineNumber(3)
        failure.assertThatCause(containsNormalizedString("Read system property 'CI' from class 'build_"))

        where:
        mechanism << SystemPropertyInjection.all("CI", "false")
    }

    @Unroll
    def "reports buildSrc build logic and tasks reading a system property set #mechanism.description via the Java API"() {
        file("buildSrc/build.gradle") << """
            System.getProperty("CI")
            tasks.classes.doLast {
                System.getProperty("CI2")
            }
        """

        when:
        mechanism.setup(this)
        instantFails(*mechanism.gradleArgs, "-DCI2=true")

        then:
        // TODO - use problems fixture, however build script class is generated
        failure.assertThatDescription(containsNormalizedString("- unknown location: read system property 'CI' from class 'build_"))
        failure.assertHasFileName("Build file '${file("buildSrc/build.gradle").absolutePath}'")
        failure.assertHasLineNumber(2)
        failure.assertThatCause(containsNormalizedString("Read system property 'CI' from class 'build_"))
        failure.assertThatCause(containsNormalizedString("Read system property 'CI2' from class 'build_"))

        where:
        mechanism << SystemPropertyInjection.all("CI", "false")
    }

    @Unroll
    def "build logic can read system property with no value without declaring access and loading fails when value set using #mechanism.description"() {
        file("buildSrc/src/main/java/SneakyPlugin.java") << """
            import ${Project.name};
            import ${Plugin.name};

            public class SneakyPlugin implements Plugin<Project> {
                public void apply(Project project) {
                    System.out.println("CI = " + System.getProperty("CI"));
                }
            }
        """
        buildFile << """
            apply plugin: SneakyPlugin
        """
        def fixture = newInstantExecutionFixture()

        when:
        instantRun()

        then:
        outputContains("CI = null")

        when:
        instantRun()

        then:
        fixture.assertStateLoaded()
        noExceptionThrown()

        when:
        mechanism.setup(this)
        instantFails(*mechanism.gradleArgs)

        then:
        failure.assertThatDescription(containsNormalizedString("- unknown location: read system property 'CI' from class '"))

        where:
        mechanism << SystemPropertyInjection.all("CI", "false")
    }

    @Unroll
    def "build logic can read system property with a default using #read.javaExpression without declaring access"() {
        file("buildSrc/src/main/java/SneakyPlugin.java") << """
            import ${Project.name};
            import ${Plugin.name};

            public class SneakyPlugin implements Plugin<Project> {
                public void apply(Project project) {
                    System.out.println("CI = " + ${read.javaExpression});
                }
            }
        """
        buildFile << """
            apply plugin: SneakyPlugin
        """
        def fixture = newInstantExecutionFixture()

        when:
        instantRun()

        then:
        outputContains("CI = $defaultValue")

        when:
        instantRun()

        then:
        fixture.assertStateLoaded()
        noExceptionThrown()

        when:
        instantFails("-DCI=$defaultValue") // use the default value

        then:
        fixture.assertStateStored()
        failure.assertThatDescription(containsNormalizedString("- unknown location: read system property 'CI' from class '"))

        when:
        instantRun("-DCI=$newValue") // undeclared inputs are not treated as inputs, but probably should be

        then:
        fixture.assertStateLoaded()
        noExceptionThrown()

        where:
        read                                                                        | defaultValue | newValue
        SystemPropertyRead.systemGetPropertyWithDefault("CI", "false")              | "false"      | "true"
        SystemPropertyRead.systemGetPropertiesGetPropertyWithDefault("CI", "false") | "false"      | "true"
        SystemPropertyRead.integerGetIntegerWithPrimitiveDefault("CI", 123)         | "123"        | "456"
        SystemPropertyRead.integerGetIntegerWithIntegerDefault("CI", 123)           | "123"        | "456"
        SystemPropertyRead.longGetLongWithPrimitiveDefault("CI", 123)               | "123"        | "456"
        SystemPropertyRead.longGetLongWithLongDefault("CI", 123)                    | "123"        | "456"
    }

    @Unroll
    def "build logic can read standard system property #prop without declaring access"() {
        file("buildSrc/src/main/java/SneakyPlugin.java") << """
            import ${Project.name};
            import ${Plugin.name};

            public class SneakyPlugin implements Plugin<Project> {
                public void apply(Project project) {
                    System.out.println("$prop = " + System.getProperty("$prop"));
                }
            }
        """
        buildFile << """
            apply plugin: SneakyPlugin
        """
        def fixture = newInstantExecutionFixture()

        when:
        instantRun()

        then:
        outputContains("$prop = ")

        when:
        instantRun()

        then:
        fixture.assertStateLoaded()
        noExceptionThrown()

        where:
        prop << [
            "os.name",
            "os.version",
            "os.arch",
            "java.version",
            "java.vm.version",
            "java.specification.version",
            "line.separator",
            "user.name",
            "user.home"
        ]
    }

    @Issue("https://github.com/gradle/gradle/issues/13155")
    def "plugin can bundle multiple resources with the same name"() {
        file("buildSrc/build.gradle") << """
            jar.from('resources1')
            jar.from('resources2')
            jar.duplicatesStrategy = DuplicatesStrategy.INCLUDE
        """
        file("buildSrc/src/main/groovy/SomePlugin.groovy") << """
            import ${Project.name}
            import ${Plugin.name}

            class SomePlugin implements Plugin<Project> {
                void apply(Project project) {
                    getClass().classLoader.getResources("file.txt").each { url ->
                        println("resource = " + url.text)
                    }
                }
            }
        """
        file("buildSrc/resources1/file.txt") << "one"
        file("buildSrc/resources2/file.txt") << "two"
        buildFile << """
            apply plugin: SomePlugin
        """

        when:
        instantRun()

        then:
        // The JVM only exposes one of the resources
        output.count("resource = ") == 1
        outputContains("resource = two")
    }
}
