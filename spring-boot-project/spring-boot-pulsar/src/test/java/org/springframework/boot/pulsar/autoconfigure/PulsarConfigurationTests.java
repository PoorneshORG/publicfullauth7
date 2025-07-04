/*
 * Copyright 2012-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.pulsar.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.AutoClusterFailover;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.pulsar.core.DefaultPulsarClientFactory;
import org.springframework.pulsar.core.DefaultSchemaResolver;
import org.springframework.pulsar.core.DefaultTopicResolver;
import org.springframework.pulsar.core.PulsarAdminBuilderCustomizer;
import org.springframework.pulsar.core.PulsarAdministration;
import org.springframework.pulsar.core.PulsarClientBuilderCustomizer;
import org.springframework.pulsar.core.PulsarClientFactory;
import org.springframework.pulsar.core.PulsarTopicBuilder;
import org.springframework.pulsar.core.SchemaResolver;
import org.springframework.pulsar.core.SchemaResolver.SchemaResolverCustomizer;
import org.springframework.pulsar.core.TopicResolver;
import org.springframework.pulsar.function.PulsarFunctionAdministration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link PulsarConfiguration}.
 *
 * @author Chris Bono
 * @author Alexander Preuß
 * @author Soby Chacko
 * @author Phillip Webb
 * @author Swamy Mavuri
 */
class PulsarConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(PulsarConfiguration.class))
		.withBean(PulsarClient.class, () -> mock(PulsarClient.class));

	@Test
	void whenHasUserDefinedConnectionDetailsBeanDoesNotAutoConfigureBean() {
		PulsarConnectionDetails customConnectionDetails = mock(PulsarConnectionDetails.class);
		this.contextRunner
			.withBean("customPulsarConnectionDetails", PulsarConnectionDetails.class, () -> customConnectionDetails)
			.run((context) -> assertThat(context).getBean(PulsarConnectionDetails.class)
				.isSameAs(customConnectionDetails));
	}

	@Test
	void whenHasUserDefinedContainerFactoryCustomizersBeanDoesNotAutoConfigureBean() {
		PulsarContainerFactoryCustomizers customizers = mock(PulsarContainerFactoryCustomizers.class);
		this.contextRunner
			.withBean("customContainerFactoryCustomizers", PulsarContainerFactoryCustomizers.class, () -> customizers)
			.run((context) -> assertThat(context).getBean(PulsarContainerFactoryCustomizers.class)
				.isSameAs(customizers));
	}

	@Nested
	class ClientTests {

		@Test
		void whenHasUserDefinedClientFactoryBeanDoesNotAutoConfigureBean() {
			PulsarClientFactory customFactory = mock(PulsarClientFactory.class);
			new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PulsarConfiguration.class))
				.withBean("customPulsarClientFactory", PulsarClientFactory.class, () -> customFactory)
				.run((context) -> assertThat(context).getBean(PulsarClientFactory.class).isSameAs(customFactory));
		}

		@Test
		void whenHasUserDefinedClientBeanDoesNotAutoConfigureBean() {
			PulsarClient customClient = mock(PulsarClient.class);
			new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PulsarConfiguration.class))
				.withBean("customPulsarClient", PulsarClient.class, () -> customClient)
				.run((context) -> assertThat(context).getBean(PulsarClient.class).isSameAs(customClient));
		}

		@Test
		void whenHasUserDefinedCustomizersAppliesInCorrectOrder() {
			PulsarConnectionDetails connectionDetails = mock(PulsarConnectionDetails.class);
			given(connectionDetails.getBrokerUrl()).willReturn("connectiondetails");
			PulsarConfigurationTests.this.contextRunner
				.withUserConfiguration(PulsarClientBuilderCustomizersConfig.class)
				.withBean(PulsarConnectionDetails.class, () -> connectionDetails)
				.withPropertyValues("spring.pulsar.client.service-url=properties")
				.run((context) -> {
					DefaultPulsarClientFactory clientFactory = context.getBean(DefaultPulsarClientFactory.class);
					Customizers<PulsarClientBuilderCustomizer, ClientBuilder> customizers = Customizers
						.of(ClientBuilder.class, PulsarClientBuilderCustomizer::customize);
					assertThat(customizers.fromField(clientFactory, "customizer")).callsInOrder(
							ClientBuilder::serviceUrl, "connectiondetails", "fromCustomizer1", "fromCustomizer2");
				});
		}

		@Test
		void whenHasUserDefinedFailoverPropertiesAddsToClient() {
			PulsarConnectionDetails connectionDetails = mock(PulsarConnectionDetails.class);
			given(connectionDetails.getBrokerUrl()).willReturn("connectiondetails");
			PulsarConfigurationTests.this.contextRunner.withBean(PulsarConnectionDetails.class, () -> connectionDetails)
				.withPropertyValues("spring.pulsar.client.service-url=properties",
						"spring.pulsar.client.failover.backup-clusters[0].service-url=backup-cluster-1",
						"spring.pulsar.client.failover.delay=15s",
						"spring.pulsar.client.failover.switch-back-delay=30s",
						"spring.pulsar.client.failover.check-interval=5s",
						"spring.pulsar.client.failover.backup-clusters[1].service-url=backup-cluster-2",
						"spring.pulsar.client.failover.backup-clusters[1].authentication.plugin-class-name="
								+ MockAuthentication.class.getName(),
						"spring.pulsar.client.failover.backup-clusters[1].authentication.param.token=1234")
				.run((context) -> {
					DefaultPulsarClientFactory clientFactory = context.getBean(DefaultPulsarClientFactory.class);
					PulsarProperties pulsarProperties = context.getBean(PulsarProperties.class);
					ClientBuilder target = mock(ClientBuilder.class);
					BiConsumer<PulsarClientBuilderCustomizer, ClientBuilder> customizeAction = PulsarClientBuilderCustomizer::customize;
					PulsarClientBuilderCustomizer pulsarClientBuilderCustomizer = (PulsarClientBuilderCustomizer) ReflectionTestUtils
						.getField(clientFactory, "customizer");
					customizeAction.accept(pulsarClientBuilderCustomizer, target);
					InOrder ordered = inOrder(target);
					ordered.verify(target).serviceUrlProvider(ArgumentMatchers.any(AutoClusterFailover.class));
					assertThat(pulsarProperties.getClient().getFailover().getDelay()).isEqualTo(Duration.ofSeconds(15));
					assertThat(pulsarProperties.getClient().getFailover().getSwitchBackDelay())
						.isEqualTo(Duration.ofSeconds(30));
					assertThat(pulsarProperties.getClient().getFailover().getCheckInterval())
						.isEqualTo(Duration.ofSeconds(5));
					assertThat(pulsarProperties.getClient().getFailover().getBackupClusters().size()).isEqualTo(2);
				});
		}

		@TestConfiguration(proxyBeanMethods = false)
		static class PulsarClientBuilderCustomizersConfig {

			@Bean
			@Order(200)
			PulsarClientBuilderCustomizer customizerFoo() {
				return (builder) -> builder.serviceUrl("fromCustomizer2");
			}

			@Bean
			@Order(100)
			PulsarClientBuilderCustomizer customizerBar() {
				return (builder) -> builder.serviceUrl("fromCustomizer1");
			}

		}

	}

	@Nested
	class AdministrationTests {

		private final ApplicationContextRunner contextRunner = PulsarConfigurationTests.this.contextRunner;

		@Test
		void whenHasUserDefinedBeanDoesNotAutoConfigureBean() {
			PulsarAdministration pulsarAdministration = mock(PulsarAdministration.class);
			this.contextRunner
				.withBean("customPulsarAdministration", PulsarAdministration.class, () -> pulsarAdministration)
				.run((context) -> assertThat(context).getBean(PulsarAdministration.class)
					.isSameAs(pulsarAdministration));
		}

		@Test
		void whenHasUserDefinedCustomizersAppliesInCorrectOrder() {
			PulsarConnectionDetails connectionDetails = mock(PulsarConnectionDetails.class);
			given(connectionDetails.getAdminUrl()).willReturn("connectiondetails");
			this.contextRunner.withUserConfiguration(PulsarAdminBuilderCustomizersConfig.class)
				.withBean(PulsarConnectionDetails.class, () -> connectionDetails)
				.withPropertyValues("spring.pulsar.admin.service-url=property")
				.run((context) -> {
					PulsarAdministration pulsarAdmin = context.getBean(PulsarAdministration.class);
					Customizers<PulsarAdminBuilderCustomizer, PulsarAdminBuilder> customizers = Customizers
						.of(PulsarAdminBuilder.class, PulsarAdminBuilderCustomizer::customize);
					assertThat(customizers.fromField(pulsarAdmin, "adminCustomizers")).callsInOrder(
							PulsarAdminBuilder::serviceHttpUrl, "connectiondetails", "fromCustomizer1",
							"fromCustomizer2");
				});
		}

		@TestConfiguration(proxyBeanMethods = false)
		static class PulsarAdminBuilderCustomizersConfig {

			@Bean
			@Order(200)
			PulsarAdminBuilderCustomizer customizerFoo() {
				return (builder) -> builder.serviceHttpUrl("fromCustomizer2");
			}

			@Bean
			@Order(100)
			PulsarAdminBuilderCustomizer customizerBar() {
				return (builder) -> builder.serviceHttpUrl("fromCustomizer1");
			}

		}

	}

	@Nested
	class SchemaResolverTests {

		private final ApplicationContextRunner contextRunner = PulsarConfigurationTests.this.contextRunner;

		@Test
		void whenHasUserDefinedBeanDoesNotAutoConfigureBean() {
			SchemaResolver schemaResolver = mock(SchemaResolver.class);
			this.contextRunner.withBean("customSchemaResolver", SchemaResolver.class, () -> schemaResolver)
				.run((context) -> assertThat(context).getBean(SchemaResolver.class).isSameAs(schemaResolver));
		}

		@Test
		void whenHasUserDefinedSchemaResolverCustomizer() {
			SchemaResolverCustomizer<DefaultSchemaResolver> customizer = (schemaResolver) -> schemaResolver
				.addCustomSchemaMapping(TestRecord.class, Schema.STRING);
			this.contextRunner.withBean("schemaResolverCustomizer", SchemaResolverCustomizer.class, () -> customizer)
				.run((context) -> assertThat(context).getBean(DefaultSchemaResolver.class)
					.satisfies(customSchemaMappingOf(TestRecord.class, Schema.STRING)));
		}

		@Test
		void whenHasDefaultsTypeMappingForPrimitiveAddsToSchemaResolver() {
			List<String> properties = new ArrayList<>();
			properties.add("spring.pulsar.defaults.type-mappings[0].message-type=" + TestRecord.CLASS_NAME);
			properties.add("spring.pulsar.defaults.type-mappings[0].schema-info.schema-type=STRING");
			this.contextRunner.withPropertyValues(properties.toArray(String[]::new))
				.run((context) -> assertThat(context).getBean(DefaultSchemaResolver.class)
					.satisfies(customSchemaMappingOf(TestRecord.class, Schema.STRING)));
		}

		@Test
		void whenHasDefaultsTypeMappingForStructAddsToSchemaResolver() {
			List<String> properties = new ArrayList<>();
			properties.add("spring.pulsar.defaults.type-mappings[0].message-type=" + TestRecord.CLASS_NAME);
			properties.add("spring.pulsar.defaults.type-mappings[0].schema-info.schema-type=JSON");
			Schema<?> expectedSchema = Schema.JSON(TestRecord.class);
			this.contextRunner.withPropertyValues(properties.toArray(String[]::new))
				.run((context) -> assertThat(context).getBean(DefaultSchemaResolver.class)
					.satisfies(customSchemaMappingOf(TestRecord.class, expectedSchema)));
		}

		@Test
		void whenHasDefaultsTypeMappingForKeyValueAddsToSchemaResolver() {
			List<String> properties = new ArrayList<>();
			properties.add("spring.pulsar.defaults.type-mappings[0].message-type=" + TestRecord.CLASS_NAME);
			properties.add("spring.pulsar.defaults.type-mappings[0].schema-info.schema-type=key-value");
			properties.add("spring.pulsar.defaults.type-mappings[0].schema-info.message-key-type=java.lang.String");
			Schema<?> expectedSchema = Schema.KeyValue(Schema.STRING, Schema.JSON(TestRecord.class),
					KeyValueEncodingType.INLINE);
			this.contextRunner.withPropertyValues(properties.toArray(String[]::new))
				.run((context) -> assertThat(context).getBean(DefaultSchemaResolver.class)
					.satisfies(customSchemaMappingOf(TestRecord.class, expectedSchema)));
		}

		private ThrowingConsumer<DefaultSchemaResolver> customSchemaMappingOf(Class<?> messageType,
				Schema<?> expectedSchema) {
			return (resolver) -> assertThat(resolver.getCustomSchemaMapping(messageType))
				.hasValueSatisfying(schemaEqualTo(expectedSchema));
		}

		private Consumer<Schema<?>> schemaEqualTo(Schema<?> expected) {
			return (actual) -> assertThat(actual.getSchemaInfo()).isEqualTo(expected.getSchemaInfo());
		}

	}

	@Nested
	class TopicResolverTests {

		private final ApplicationContextRunner contextRunner = PulsarConfigurationTests.this.contextRunner;

		@Test
		void whenHasUserDefinedBeanDoesNotAutoConfigureBean() {
			TopicResolver topicResolver = mock(TopicResolver.class);
			this.contextRunner.withBean("customTopicResolver", TopicResolver.class, () -> topicResolver)
				.run((context) -> assertThat(context).getBean(TopicResolver.class).isSameAs(topicResolver));
		}

		@Test
		void whenHasDefaultsTypeMappingAddsToSchemaResolver() {
			List<String> properties = new ArrayList<>();
			properties.add("spring.pulsar.defaults.type-mappings[0].message-type=" + TestRecord.CLASS_NAME);
			properties.add("spring.pulsar.defaults.type-mappings[0].topic-name=foo-topic");
			properties.add("spring.pulsar.defaults.type-mappings[1].message-type=java.lang.String");
			properties.add("spring.pulsar.defaults.type-mappings[1].topic-name=string-topic");
			this.contextRunner.withPropertyValues(properties.toArray(String[]::new))
				.run((context) -> assertThat(context).getBean(TopicResolver.class)
					.asInstanceOf(InstanceOfAssertFactories.type(DefaultTopicResolver.class))
					.satisfies((resolver) -> {
						assertThat(resolver.getCustomTopicMapping(TestRecord.class)).hasValue("foo-topic");
						assertThat(resolver.getCustomTopicMapping(String.class)).hasValue("string-topic");
					}));
		}

	}

	@Nested
	class TopicBuilderTests {

		private final ApplicationContextRunner contextRunner = PulsarConfigurationTests.this.contextRunner;

		@Test
		void whenHasUserDefinedBeanDoesNotAutoConfigureBean() {
			PulsarTopicBuilder topicBuilder = mock(PulsarTopicBuilder.class);
			this.contextRunner.withBean("customPulsarTopicBuilder", PulsarTopicBuilder.class, () -> topicBuilder)
				.run((context) -> assertThat(context).getBean(PulsarTopicBuilder.class).isSameAs(topicBuilder));
		}

		@Test
		void whenHasDefaultsTopicDisabledPropertyDoesNotCreateBean() {
			this.contextRunner.withPropertyValues("spring.pulsar.defaults.topic.enabled=false")
				.run((context) -> assertThat(context).doesNotHaveBean(PulsarTopicBuilder.class));
		}

		@Test
		void whenHasDefaultsTenantAndNamespaceAppliedToTopicBuilder() {
			List<String> properties = new ArrayList<>();
			properties.add("spring.pulsar.defaults.topic.tenant=my-tenant");
			properties.add("spring.pulsar.defaults.topic.namespace=my-namespace");
			this.contextRunner.withPropertyValues(properties.toArray(String[]::new))
				.run((context) -> assertThat(context).getBean(PulsarTopicBuilder.class)
					.asInstanceOf(InstanceOfAssertFactories.type(PulsarTopicBuilder.class))
					.satisfies((topicBuilder) -> {
						assertThat(topicBuilder).hasFieldOrPropertyWithValue("defaultTenant", "my-tenant");
						assertThat(topicBuilder).hasFieldOrPropertyWithValue("defaultNamespace", "my-namespace");
					}));
		}

		@Test
		void beanHasScopePrototype() {
			this.contextRunner.run((context) -> assertThat(context.getBean(PulsarTopicBuilder.class))
				.isNotSameAs(context.getBean(PulsarTopicBuilder.class)));
		}

	}

	@Nested
	class FunctionAdministrationTests {

		private final ApplicationContextRunner contextRunner = PulsarConfigurationTests.this.contextRunner;

		@Test
		void whenNoPropertiesAddsFunctionAdministrationBean() {
			this.contextRunner.run((context) -> assertThat(context).getBean(PulsarFunctionAdministration.class)
				.hasFieldOrPropertyWithValue("failFast", Boolean.TRUE)
				.hasFieldOrPropertyWithValue("propagateFailures", Boolean.TRUE)
				.hasFieldOrPropertyWithValue("propagateStopFailures", Boolean.FALSE)
				.hasNoNullFieldsOrProperties() // ensures object providers set
				.extracting("pulsarAdministration")
				.isSameAs(context.getBean(PulsarAdministration.class)));
		}

		@Test
		void whenHasFunctionPropertiesAppliesPropertiesToBean() {
			List<String> properties = new ArrayList<>();
			properties.add("spring.pulsar.function.fail-fast=false");
			properties.add("spring.pulsar.function.propagate-failures=false");
			properties.add("spring.pulsar.function.propagate-stop-failures=true");
			this.contextRunner.withPropertyValues(properties.toArray(String[]::new))
				.run((context) -> assertThat(context).getBean(PulsarFunctionAdministration.class)
					.hasFieldOrPropertyWithValue("failFast", Boolean.FALSE)
					.hasFieldOrPropertyWithValue("propagateFailures", Boolean.FALSE)
					.hasFieldOrPropertyWithValue("propagateStopFailures", Boolean.TRUE));
		}

		@Test
		void whenHasFunctionDisabledPropertyDoesNotCreateBean() {
			this.contextRunner.withPropertyValues("spring.pulsar.function.enabled=false")
				.run((context) -> assertThat(context).doesNotHaveBean(PulsarFunctionAdministration.class));
		}

		@Test
		void whenHasCustomFunctionAdministrationBean() {
			PulsarFunctionAdministration functionAdministration = mock(PulsarFunctionAdministration.class);
			this.contextRunner.withBean(PulsarFunctionAdministration.class, () -> functionAdministration)
				.run((context) -> assertThat(context).getBean(PulsarFunctionAdministration.class)
					.isSameAs(functionAdministration));
		}

	}

	record TestRecord() {

		private static final String CLASS_NAME = TestRecord.class.getName();

	}

}
