/*
 * Copyright 2016 the original author or authors.
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
 *
 */

package org.springframework.data.gemfire.config.annotation;

import static java.util.Arrays.stream;
import static org.springframework.data.gemfire.util.ArrayUtils.defaultIfEmpty;
import static org.springframework.data.gemfire.util.ArrayUtils.nullSafeArray;
import static org.springframework.data.gemfire.util.CollectionUtils.nullSafeMap;
import static org.springframework.data.gemfire.util.RuntimeExceptionFactory.newIllegalArgumentException;
import static org.springframework.data.gemfire.util.RuntimeExceptionFactory.newIllegalStateException;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AspectJTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.data.gemfire.FixedPartitionAttributesFactoryBean;
import org.springframework.data.gemfire.LocalRegionFactoryBean;
import org.springframework.data.gemfire.PartitionAttributesFactoryBean;
import org.springframework.data.gemfire.PartitionedRegionFactoryBean;
import org.springframework.data.gemfire.RegionAttributesFactoryBean;
import org.springframework.data.gemfire.RegionLookupFactoryBean;
import org.springframework.data.gemfire.ReplicatedRegionFactoryBean;
import org.springframework.data.gemfire.ScopeType;
import org.springframework.data.gemfire.client.ClientRegionFactoryBean;
import org.springframework.data.gemfire.config.annotation.support.GemFireCacheTypeAwareRegionFactoryBean;
import org.springframework.data.gemfire.config.annotation.support.GemFireComponentClassTypeScanner;
import org.springframework.data.gemfire.config.xml.GemfireConstants;
import org.springframework.data.gemfire.mapping.GemfireMappingContext;
import org.springframework.data.gemfire.mapping.GemfirePersistentEntity;
import org.springframework.data.gemfire.mapping.GemfirePersistentProperty;
import org.springframework.data.gemfire.mapping.annotation.ClientRegion;
import org.springframework.data.gemfire.mapping.annotation.LocalRegion;
import org.springframework.data.gemfire.mapping.annotation.PartitionRegion;
import org.springframework.data.gemfire.mapping.annotation.ReplicateRegion;
import org.springframework.data.gemfire.util.CollectionUtils;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * The {@link EntityDefinedRegionsConfiguration} class is Spring {@link ImportBeanDefinitionRegistrar} used in
 * the {@link EnableEntityDefinedRegions} annotation to dynamically create GemFire/Geode {@link Region Regions}
 * based on the application persistent entity classes.
 *
 * @author John Blum
 * @see java.lang.ClassLoader
 * @see java.lang.annotation.Annotation
 * @see org.apache.geode.cache.Region
 * @see org.springframework.beans.factory.BeanClassLoaderAware
 * @see org.springframework.beans.factory.BeanFactory
 * @see org.springframework.beans.factory.BeanFactoryAware
 * @see org.springframework.beans.factory.config.BeanDefinition
 * @see org.springframework.beans.factory.support.BeanDefinitionBuilder
 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry
 * @see org.springframework.context.annotation.ImportBeanDefinitionRegistrar
 * @see org.springframework.data.gemfire.FixedPartitionAttributesFactoryBean
 * @see org.springframework.data.gemfire.LocalRegionFactoryBean
 * @see org.springframework.data.gemfire.PartitionAttributesFactoryBean
 * @see org.springframework.data.gemfire.PartitionedRegionFactoryBean
 * @see org.springframework.data.gemfire.RegionAttributesFactoryBean
 * @see org.springframework.data.gemfire.ReplicatedRegionFactoryBean
 * @see org.springframework.data.gemfire.client.ClientRegionFactoryBean
 * @see org.springframework.data.gemfire.config.annotation.support.GemFireCacheTypeAwareRegionFactoryBean
 * @see org.springframework.data.gemfire.config.annotation.support.GemFireComponentClassTypeScanner
 * @see org.springframework.data.gemfire.mapping.GemfireMappingContext
 * @see org.springframework.data.gemfire.mapping.GemfirePersistentEntity
 * @see org.springframework.data.gemfire.mapping.annotation.ClientRegion
 * @see org.springframework.data.gemfire.mapping.annotation.LocalRegion
 * @see org.springframework.data.gemfire.mapping.annotation.PartitionRegion
 * @see org.springframework.data.gemfire.mapping.annotation.ReplicateRegion
 * @see org.springframework.data.gemfire.mapping.annotation.Region
 * @since 1.9.0
 */
public class EntityDefinedRegionsConfiguration
		implements BeanClassLoaderAware, BeanFactoryAware, ImportBeanDefinitionRegistrar {

	protected static final Class<? extends RegionLookupFactoryBean> DEFAULT_REGION_FACTORY_BEAN_CLASS =
		GemFireCacheTypeAwareRegionFactoryBean.class;

	protected static final Map<Class<? extends Annotation>, Class<? extends RegionLookupFactoryBean>> regionAnnotationToRegionFactoryBeanClass =
		new HashMap<>();

	static {
		regionAnnotationToRegionFactoryBeanClass.put(ClientRegion.class, ClientRegionFactoryBean.class);
		regionAnnotationToRegionFactoryBeanClass.put(LocalRegion.class, LocalRegionFactoryBean.class);
		regionAnnotationToRegionFactoryBeanClass.put(PartitionRegion.class, PartitionedRegionFactoryBean.class);
		regionAnnotationToRegionFactoryBeanClass.put(ReplicateRegion.class, ReplicatedRegionFactoryBean.class);
		regionAnnotationToRegionFactoryBeanClass.put(org.springframework.data.gemfire.mapping.annotation.Region.class,
			DEFAULT_REGION_FACTORY_BEAN_CLASS);
	}

	private BeanFactory beanFactory;

	private ClassLoader beanClassLoader;

	private GemfireMappingContext mappingContext;

	@Autowired(required = false)
	private List<RegionConfigurer> regionConfigurers = Collections.emptyList();

	/**
	 * Returns the {@link Annotation} {@link Class type} that configures and creates {@link Region Regions}
	 * for application persistent entities.
	 *
	 * @return the {@link Annotation} {@link Class type} that configures and creates {@link Region Regions}
	 * for application persistent entities.
	 * @see org.springframework.data.gemfire.config.annotation.EnableEntityDefinedRegions
	 * @see java.lang.annotation.Annotation
	 * @see java.lang.Class
	 */
	protected Class<? extends Annotation> getAnnotationType() {
		return EnableEntityDefinedRegions.class;
	}

	/**
	 * Returns the name of the {@link Annotation} type that configures and creates {@link Region Regions}
	 * for application persistent entities.
	 *
	 * @return the name of the {@link Annotation} type that configures and creates {@link Region Regions}
	 * for application persistent entities.
	 * @see java.lang.Class#getName()
	 * @see #getAnnotationType()
	 */
	protected String getAnnotationTypeName() {
		return getAnnotationType().getName();
	}

	/**
	 * Returns the simple name of the {@link Annotation} type that configures and creates {@link Region Regions}
	 * for application persistent entities.
	 *
	 * @return the simple name of the {@link Annotation} type that configures and creates {@link Region Regions}
	 * for application persistent entities.
	 * @see java.lang.Class#getSimpleName()
	 * @see #getAnnotationType()
	 */
	@SuppressWarnings("unused")
	protected String getAnnotationTypeSimpleName() {
		return getAnnotationType().getSimpleName();
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	/* (non-Javadoc) */
	@SuppressWarnings("unused")
	protected ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	/* (non-Javadoc) */
	protected BeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	/* (non-Javadoc) */
	protected boolean isAnnotationPresent(AnnotationMetadata importingClassMetadata) {
		return isAnnotationPresent(importingClassMetadata, getAnnotationTypeName());
	}

	/* (non-Javadoc) */
	protected boolean isAnnotationPresent(AnnotationMetadata importingClassMetadata, String annotationName) {
		return importingClassMetadata.hasAnnotation(annotationName);
	}

	/* (non-Javadoc) */
	protected AnnotationAttributes getAnnotationAttributes(Annotation annotation) {
		return AnnotationAttributes.fromMap(AnnotationUtils.getAnnotationAttributes(annotation));
	}

	/* (non-Javadoc) */
	protected AnnotationAttributes getAnnotationAttributes(AnnotationMetadata importingClassMetadata) {
		return getAnnotationAttributes(importingClassMetadata, getAnnotationTypeName());
	}

	/* (non-Javadoc) */
	protected AnnotationAttributes getAnnotationAttributes(AnnotationMetadata importingClassMetadata,
			String annotationName) {

		return AnnotationAttributes.fromMap(importingClassMetadata.getAnnotationAttributes(annotationName));
	}

	/* (non-Javadoc) */
	protected GemfirePersistentEntity<?> getPersistentEntity(Class<?> persistentEntityType) {

		return resolveMappingContext().getPersistentEntity(persistentEntityType).orElseThrow(
			() -> newIllegalStateException("PersistentEntity for type [%s] not found", persistentEntityType));
	}

	/* (non-Javadoc) */
	protected GemfireMappingContext resolveMappingContext() {
		return Optional.ofNullable(this.mappingContext).orElseGet(() -> {
			try {
				this.mappingContext = getBeanFactory().getBean(GemfireMappingContext.class);
			}
			catch (Throwable ignore) {
				this.mappingContext = new GemfireMappingContext();
			}

			return this.mappingContext;
		});
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {

		if (isAnnotationPresent(importingClassMetadata)) {

			AnnotationAttributes enableEntityDefinedRegionsAttributes = getAnnotationAttributes(importingClassMetadata);

			boolean strict = enableEntityDefinedRegionsAttributes.getBoolean("strict");

			newGemFireComponentClassTypeScanner(importingClassMetadata, enableEntityDefinedRegionsAttributes).scan()
				.forEach(persistentEntityClass -> {

					GemfirePersistentEntity persistentEntity = getPersistentEntity(persistentEntityClass);

					registerRegionBeanDefinition(persistentEntity, strict, registry);
					postProcess(importingClassMetadata, registry, persistentEntity);
				});
		}
	}

	/* (non-Javadoc) */
	protected GemFireComponentClassTypeScanner newGemFireComponentClassTypeScanner(
			AnnotationMetadata importingClassMetadata, AnnotationAttributes enableEntityDefinedRegionsAttributes) {

		Set<String> resolvedBasePackages =
			resolveBasePackages(importingClassMetadata, enableEntityDefinedRegionsAttributes);

		return GemFireComponentClassTypeScanner.from(resolvedBasePackages).with(resolveBeanClassLoader())
			.withExcludes(resolveExcludes(enableEntityDefinedRegionsAttributes))
			.withIncludes(resolveIncludes(enableEntityDefinedRegionsAttributes))
			.withIncludes(regionAnnotatedPersistentEntityTypeFilters());
	}

	/* (non-Javadoc) */
	protected Set<String> resolveBasePackages(AnnotationMetadata importingClassMetaData,
			AnnotationAttributes enableEntityDefinedRegionAttributes) {

		Set<String> resolvedBasePackages = new HashSet<>();

		Collections.addAll(resolvedBasePackages, nullSafeArray(defaultIfEmpty(
			enableEntityDefinedRegionAttributes.getStringArray("basePackages"),
				enableEntityDefinedRegionAttributes.getStringArray("value")),
					String.class));

		stream(nullSafeArray(enableEntityDefinedRegionAttributes.getClassArray(
			"basePackageClasses"), Class.class))
				.forEach(type -> resolvedBasePackages.add(type.getPackage().getName()));

		if (resolvedBasePackages.isEmpty()) {
			resolvedBasePackages.add(ClassUtils.getPackageName(importingClassMetaData.getClassName()));
		}

		return resolvedBasePackages;
	}

	/* (non-Javadoc) */
	protected ClassLoader resolveBeanClassLoader() {
		return Optional.ofNullable(this.beanClassLoader)
			.orElseGet(() -> Thread.currentThread().getContextClassLoader());
	}

	/* (non-Javadoc) */
	protected Iterable<TypeFilter> resolveExcludes(AnnotationAttributes enableEntityDefinedRegionsAttributes) {
		return parseFilters(enableEntityDefinedRegionsAttributes.getAnnotationArray("excludeFilters"));
	}

	/* (non-Javadoc) */
	protected Iterable<TypeFilter> resolveIncludes(AnnotationAttributes enableEntityDefinedRegionsAttributes) {
		return parseFilters(enableEntityDefinedRegionsAttributes.getAnnotationArray("includeFilters"));
	}

	/* (non-Javadoc) */
	private Iterable<TypeFilter> parseFilters(AnnotationAttributes[] componentScanFilterAttributes) {

		Set<TypeFilter> typeFilters = new HashSet<>();

		stream(nullSafeArray(componentScanFilterAttributes, AnnotationAttributes.class))
			.forEach(filterAttributes -> CollectionUtils.addAll(typeFilters, typeFiltersFor(filterAttributes)));

		return typeFilters;
	}

	/* (non-Javadoc) */
	@SuppressWarnings("unchecked")
	private Iterable<TypeFilter> typeFiltersFor(AnnotationAttributes filterAttributes) {

		Set<TypeFilter> typeFilters = new HashSet<>();
		FilterType filterType = filterAttributes.getEnum("type");

		stream(nullSafeArray(filterAttributes.getClassArray("value"), Class.class))
			.forEach(filterClass -> {
				switch (filterType) {
					case ANNOTATION:
						Assert.isAssignable(Annotation.class, filterClass,
							String.format("@ComponentScan.Filter class [%s] must be an Annotation", filterClass));
						typeFilters.add(new AnnotationTypeFilter((Class<Annotation>) filterClass));
						break;
					case ASSIGNABLE_TYPE:
						typeFilters.add(new AssignableTypeFilter(filterClass));
						break;
					case CUSTOM:
						Assert.isAssignable(TypeFilter.class, filterClass,
							String.format("@ComponentScan.Filter class [%s] must be a TypeFilter", filterClass));
						typeFilters.add(BeanUtils.instantiateClass(filterClass, TypeFilter.class));
						break;
					default:
						throw newIllegalArgumentException(
							"Illegal filter type [%s] when 'value' or 'classes' are specified", filterType);
				}

				for (String pattern : nullSafeGetPatterns(filterAttributes)) {
					switch (filterType) {
						case ASPECTJ:
							typeFilters.add(new AspectJTypeFilter(pattern, resolveBeanClassLoader()));
							break;
						case REGEX:
							typeFilters.add(new RegexPatternTypeFilter(Pattern.compile(pattern)));
							break;
						default:
							throw newIllegalArgumentException(
								"Illegal filter type [%s] when 'patterns' are specified", filterType);
					}
				}
		});

		return typeFilters;
	}

	/**
	 * Safely reads the {@code pattern} attribute from the given {@link AnnotationAttributes}
	 * and returns an empty array if the attribute is not present.
	 *
	 * @param filterAttributes {@link AnnotationAttributes} from which to extract the {@code pattern} attribute value.
	 * @return a {@link String} array.
	 */
	private String[] nullSafeGetPatterns(AnnotationAttributes filterAttributes) {
		try {
			return nullSafeArray(filterAttributes.getStringArray("pattern"), String.class);
		}
		catch (IllegalArgumentException ignore) {
			return new String[0];
		}
	}

	/* (non-Javadoc) */
	@SuppressWarnings("unchecked")
	protected Iterable<TypeFilter> regionAnnotatedPersistentEntityTypeFilters() {

		Set<TypeFilter> regionAnnotatedPersistentEntityTypeFilters = new HashSet<>();

		org.springframework.data.gemfire.mapping.annotation.Region.REGION_ANNOTATION_TYPES.forEach(
			annotationType -> regionAnnotatedPersistentEntityTypeFilters.add(new AnnotationTypeFilter(annotationType)));

		return regionAnnotatedPersistentEntityTypeFilters;
	}

	/* (non-Javadoc) */
	protected void registerRegionBeanDefinition(GemfirePersistentEntity persistentEntity, boolean strict,
			BeanDefinitionRegistry registry) {

		BeanDefinitionBuilder regionFactoryBeanBuilder =
			BeanDefinitionBuilder.genericBeanDefinition(resolveRegionFactoryBeanClass(persistentEntity))
				.addPropertyReference("cache", GemfireConstants.DEFAULT_GEMFIRE_CACHE_NAME)
				.addPropertyValue("regionConfigurers", resolveRegionConfigurers())
				.addPropertyValue("close", false);

		setRegionAttributes(persistentEntity, regionFactoryBeanBuilder, strict);

		registry.registerBeanDefinition(persistentEntity.getRegionName(),
			regionFactoryBeanBuilder.getBeanDefinition());
	}

	/* (non-Javadoc) */
	private List<RegionConfigurer> resolveRegionConfigurers() {

		return Optional.ofNullable(this.regionConfigurers)
			.filter(regionConfigurers -> !regionConfigurers.isEmpty())
			.orElseGet(() ->
				Optional.ofNullable(this.beanFactory)
					.filter(beanFactory -> beanFactory instanceof ListableBeanFactory)
					.map(beanFactory -> {
						Map<String, RegionConfigurer> beansOfType = ((ListableBeanFactory) beanFactory)
							.getBeansOfType(RegionConfigurer.class, true, true);

						return nullSafeMap(beansOfType).values().stream().collect(Collectors.toList());
					})
					.orElseGet(Collections::emptyList)
			);
	}

	/* (non-Javadoc) */
	@SuppressWarnings("unchecked")
	protected Class<? extends RegionLookupFactoryBean> resolveRegionFactoryBeanClass(
			GemfirePersistentEntity persistentEntity) {

		return Optional.<Class<? extends RegionLookupFactoryBean>>ofNullable(
			regionAnnotationToRegionFactoryBeanClass.get(persistentEntity.getRegionAnnotationType()))
				.orElse(DEFAULT_REGION_FACTORY_BEAN_CLASS);
	}

	/* (non-Javadoc) */
	protected BeanDefinitionBuilder setRegionAttributes(GemfirePersistentEntity persistentEntity,
			BeanDefinitionBuilder regionFactoryBeanBuilder, boolean strict) {

		Optional.ofNullable(persistentEntity.getRegionAnnotation()).ifPresent(regionAnnotation -> {
			AnnotationAttributes regionAnnotationAttributes = getAnnotationAttributes(regionAnnotation);

			if (strict) {
				regionFactoryBeanBuilder.addPropertyValue("keyConstraint", resolveIdType(persistentEntity));
				regionFactoryBeanBuilder.addPropertyValue("valueConstraint", resolveDomainType(persistentEntity));
			}

			if (regionAnnotationAttributes.containsKey("diskStoreName")) {
				String diskStoreName = regionAnnotationAttributes.getString("diskStoreName");

				setPropertyValueIfNotDefault(regionFactoryBeanBuilder, "diskStoreName",
					diskStoreName, "");

				if (StringUtils.hasText(diskStoreName)) {
					regionFactoryBeanBuilder.addDependsOn(diskStoreName);
				}
			}

			if (regionAnnotationAttributes.containsKey("ignoreIfExists")) {
				regionFactoryBeanBuilder.addPropertyValue("lookupEnabled",
					regionAnnotationAttributes.getBoolean("ignoreIfExists"));
			}

			if (regionAnnotationAttributes.containsKey("persistent")) {
				setPropertyValueIfNotDefault(regionFactoryBeanBuilder, "persistent",
					regionAnnotationAttributes.getBoolean("persistent"), false);
			}

			BeanDefinitionBuilder regionAttributesFactoryBeanBuilder =
				resolveRegionAttributesFactoryBeanBuilder(regionAnnotation, regionFactoryBeanBuilder);

			if (regionAnnotationAttributes.containsKey("diskSynchronous")) {
				setPropertyValueIfNotDefault(regionAttributesFactoryBeanBuilder, "diskSynchronous",
					regionAnnotationAttributes.getBoolean("diskSynchronous"), true);
			}

			if (regionAnnotationAttributes.containsKey("ignoreJta")) {
				setPropertyValueIfNotDefault(regionAttributesFactoryBeanBuilder, "ignoreJTA",
					regionAnnotationAttributes.getBoolean("ignoreJta"), false);
			}

			setClientRegionAttributes(regionAnnotationAttributes, regionFactoryBeanBuilder);

			setPartitionRegionAttributes(regionAnnotationAttributes, regionFactoryBeanBuilder,
				regionAttributesFactoryBeanBuilder);

			setReplicateRegionAttributes(regionAnnotationAttributes, regionFactoryBeanBuilder);
		});

		return regionFactoryBeanBuilder;
	}

	/* (non-Javadoc) */
	protected Class<?> resolveDomainType(GemfirePersistentEntity persistentEntity) {
		return Optional.ofNullable(persistentEntity.getType()).orElse(Object.class);
	}

	/* (non-Javadoc) */
	@SuppressWarnings("unchecked")
	protected Class<?> resolveIdType(GemfirePersistentEntity persistentEntity) {

		return (Class<?>) persistentEntity.getIdProperty()
			.map(idProperty -> ((GemfirePersistentProperty) idProperty).getActualType())
			.orElse(Object.class);
	}

	/* (non-Javadoc) */
	protected BeanDefinitionBuilder resolveRegionAttributesFactoryBeanBuilder(Annotation regionAnnotation,
			BeanDefinitionBuilder regionFactoryBeanBuilder) {

		BeanDefinitionBuilder regionAttributesFactoryBeanBuilder = regionFactoryBeanBuilder;

		if (!ClientRegion.class.isAssignableFrom(regionAnnotation.annotationType())) {
			regionAttributesFactoryBeanBuilder =
				BeanDefinitionBuilder.genericBeanDefinition(RegionAttributesFactoryBean.class);

			regionFactoryBeanBuilder.addPropertyValue("attributes",
				regionAttributesFactoryBeanBuilder.getBeanDefinition());
		}

		return regionAttributesFactoryBeanBuilder;
	}

	/* (non-Javadoc) */
	protected BeanDefinitionBuilder setClientRegionAttributes(AnnotationAttributes regionAnnotationAttributes,
			BeanDefinitionBuilder regionFactoryBeanBuilder) {

		if (regionAnnotationAttributes.containsKey("poolName")) {
			setPropertyValueIfNotDefault(regionFactoryBeanBuilder, "poolName",
				regionAnnotationAttributes.getString("poolName"), null);
		}

		if (regionAnnotationAttributes.containsKey("shortcut")) {
			setPropertyValueIfNotDefault(regionFactoryBeanBuilder, "shortcut",
				regionAnnotationAttributes.getEnum("shortcut"), ClientRegionShortcut.PROXY);
		}

		return regionFactoryBeanBuilder;
	}

	/* (non-Javadoc) */
	protected BeanDefinitionBuilder setPartitionRegionAttributes(AnnotationAttributes regionAnnotationAttributes,
			BeanDefinitionBuilder regionFactoryBeanBuilder, BeanDefinitionBuilder regionAttributesFactoryBeanBuilder) {

		if (regionAnnotationAttributes.containsKey("redundantCopies")) {
			BeanDefinitionBuilder partitionAttributesFactoryBeanBuilder =
				BeanDefinitionBuilder.genericBeanDefinition(PartitionAttributesFactoryBean.class);

			String collocatedWith = regionAnnotationAttributes.getString("collocatedWith");

			setPropertyValueIfNotDefault(partitionAttributesFactoryBeanBuilder, "colocatedWith", collocatedWith, "");

			if (StringUtils.hasText(collocatedWith)) {
				regionFactoryBeanBuilder.addDependsOn(collocatedWith);
			}

			setPropertyReferenceIfSet(partitionAttributesFactoryBeanBuilder, "partitionResolver",
				regionAnnotationAttributes.getString("partitionResolverName"));

			setPropertyValueIfNotDefault(partitionAttributesFactoryBeanBuilder, "redundantCopies",
				regionAnnotationAttributes.<Integer>getNumber("redundantCopies"), 0);

			setFixedPartitionRegionAttributes(regionAnnotationAttributes, partitionAttributesFactoryBeanBuilder);

			regionAttributesFactoryBeanBuilder.addPropertyValue("partitionAttributes",
				partitionAttributesFactoryBeanBuilder.getBeanDefinition());
		}

		return regionAttributesFactoryBeanBuilder;
	}

	/* (non-Javadoc) */
	protected BeanDefinitionBuilder setFixedPartitionRegionAttributes(AnnotationAttributes regionAnnotationAttributes,
			BeanDefinitionBuilder partitionAttributesFactoryBeanBuilder) {

		PartitionRegion.FixedPartition[] fixedPartitions = nullSafeArray(regionAnnotationAttributes.getAnnotationArray(
			"fixedPartitions", PartitionRegion.FixedPartition.class), PartitionRegion.FixedPartition.class);

		if (!ObjectUtils.isEmpty(fixedPartitions)) {

			ManagedList<BeanDefinition> fixedPartitionAttributesFactoryBeans =
				new ManagedList<BeanDefinition>(fixedPartitions.length);

			for (PartitionRegion.FixedPartition fixedPartition : fixedPartitions) {
				BeanDefinitionBuilder fixedPartitionAttributesFactoryBeanBuilder =
					BeanDefinitionBuilder.genericBeanDefinition(FixedPartitionAttributesFactoryBean.class);

				fixedPartitionAttributesFactoryBeanBuilder.addPropertyValue("partitionName", fixedPartition.name());

				setPropertyValueIfNotDefault(fixedPartitionAttributesFactoryBeanBuilder, "primary",
					fixedPartition.primary(), false);

				setPropertyValueIfNotDefault(fixedPartitionAttributesFactoryBeanBuilder, "numBuckets",
					fixedPartition.numBuckets(), 1);

				fixedPartitionAttributesFactoryBeans.add(
					fixedPartitionAttributesFactoryBeanBuilder.getBeanDefinition());
			}

			partitionAttributesFactoryBeanBuilder.addPropertyValue("fixedPartitionAttributes",
				fixedPartitionAttributesFactoryBeans);
		}

		return partitionAttributesFactoryBeanBuilder;
	}

	/* (non-Javadoc) */
	protected BeanDefinitionBuilder setReplicateRegionAttributes(AnnotationAttributes regionAnnotationAttributes,
			BeanDefinitionBuilder regionFactoryBeanBuilder) {

		if (regionAnnotationAttributes.containsKey("scope")) {
			setPropertyValueIfNotDefault(regionFactoryBeanBuilder, "scope",
				regionAnnotationAttributes.<ScopeType>getEnum("scope").getScope(),
					ScopeType.DISTRIBUTED_NO_ACK);
		}

		return regionFactoryBeanBuilder;
	}

	/* (non-Javadoc) */
	private <T> BeanDefinitionBuilder setPropertyReferenceIfSet(BeanDefinitionBuilder beanDefinitionBuilder,
			String propertyName, String beanName) {

		return (StringUtils.hasText(beanName)
			? beanDefinitionBuilder.addPropertyReference(propertyName, beanName)
			: beanDefinitionBuilder);
	}

	/* (non-Javadoc) */
	private <T> BeanDefinitionBuilder setPropertyValueIfNotDefault(BeanDefinitionBuilder beanDefinitionBuilder,
			String propertyName, T value, T defaultValue) {

		return (value != null && !value.equals(defaultValue)
			? beanDefinitionBuilder.addPropertyValue(propertyName, value)
			: beanDefinitionBuilder);
	}

	/**
	 * Performs addition post processing on the {@link GemfirePersistentEntity} to offer additional feature support
	 * (e.g. dynamic Index creation).
	 *
	 * @param importingClassMetadata {@link AnnotationMetadata} for the importing application class.
	 * @param registry {@link BeanDefinitionRegistry} used to register Spring bean definitions.
	 * @param persistentEntity {@link GemfirePersistentEntity} to process.
	 * @return the given {@link GemfirePersistentEntity}.
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry
	 * @see org.springframework.core.type.AnnotationMetadata
	 * @see org.springframework.data.gemfire.mapping.GemfirePersistentEntity
	 */
	protected GemfirePersistentEntity<?> postProcess(AnnotationMetadata importingClassMetadata,
			BeanDefinitionRegistry registry, GemfirePersistentEntity<?> persistentEntity) {

		return persistentEntity;
	}
}
