package com.xxl.conf.core.spring;

import com.xxl.conf.core.XxlConfClient;
import com.xxl.conf.core.annotation.XxlConf;
import com.xxl.conf.core.core.XxlConfLocalCacheConf;
import com.xxl.conf.core.core.XxlConfZkConf;
import com.xxl.conf.core.exception.XxlConfException;
import com.xxl.conf.core.listener.impl.AnnoRefreshXxlConfListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionVisitor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringValueResolver;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * rewrite PropertyPlaceholderConfigurer
 *
 * @author xuxueli 2015-9-12 19:42:49
 */
public class XxlConfFactory extends PropertySourcesPlaceholderConfigurer {
	private static Logger logger = LoggerFactory.getLogger(XxlConfFactory.class);

	// ---------------------- init/destroy ----------------------

	public void init(){
	}
	public void destroy(){
		XxlConfLocalCacheConf.destroy();
		XxlConfZkConf.destroy();
	}


	// ---------------------- spring xml/annotation conf ----------------------

	/**
	 * xxl conf BeanDefinitionVisitor
	 *
	 * @return
	 */
	private static BeanDefinitionVisitor getXxlConfBeanDefinitionVisitor(){
		// xxl conf StringValueResolver
		StringValueResolver xxlConfValueResolver = new StringValueResolver() {

			String placeholderPrefix = "${";
			String placeholderSuffix = "}";

			@Override
			public String resolveStringValue(String strVal) {
				StringBuffer buf = new StringBuffer(strVal);

				// replace by xxl-conf, if the value match '${***}'
				boolean start = strVal.startsWith(placeholderPrefix);
				boolean end = strVal.endsWith(placeholderSuffix);

				while (start && end) {
					// replace by xxl-conf
					String key = buf.substring(placeholderPrefix.length(), buf.length() - placeholderSuffix.length());
					String zkValue = XxlConfClient.get(key, "");
					buf = new StringBuffer(zkValue);

					// loop replace like "${aaa} ... aaa=${bbb}"
					start = buf.toString().startsWith(placeholderPrefix);
					end = buf.toString().endsWith(placeholderSuffix);
					logger.info(">>>>>>>>>>> xxl conf, resolved placeholder success, [{}={}]", key, zkValue);
				}

				return buf.toString();
			}
		};

		// xxl conf BeanDefinitionVisitor
		BeanDefinitionVisitor xxlConfVisitor = new BeanDefinitionVisitor(xxlConfValueResolver);
		return xxlConfVisitor;
	}

	/**
	 * refresh bean with xxl conf (all)
	 *
	 * @param beanWithXxlConf
	 */
	public static void refreshBeanWithXxlConf(Object beanWithXxlConf, List<Field> annoBeanFields){
		for (Field annoField : annoBeanFields) {
			XxlConf xxlConf = annoField.getAnnotation(XxlConf.class);
			String confKey = xxlConf.value();

			String confValue = XxlConfClient.get(confKey, xxlConf.defaultValue());

			annoField.setAccessible(true);
			try {
				annoField.set(beanWithXxlConf, confValue);
			} catch (IllegalAccessException e) {
				throw new XxlConfException(e);
			}
			logger.info(">>>>>>>>>>> xxl conf, refreshBeanWithXxlConf success, {}:[{}={}]", beanWithXxlConf, confKey, confValue);
			if (xxlConf.callback()) {
				AnnoRefreshXxlConfListener.addKeyObject(confKey, beanWithXxlConf, annoField);
			}
		}
	}

	@Override
	protected void processProperties(ConfigurableListableBeanFactory beanFactoryToProcess, ConfigurablePropertyResolver propertyResolver) throws BeansException {
		//super.processProperties(beanFactoryToProcess, propertyResolver);

		// xxl conf BeanDefinitionVisitor
		BeanDefinitionVisitor xxlConfDBVisitor = getXxlConfBeanDefinitionVisitor();

		// visit bean definition
		String[] beanNames = beanFactoryToProcess.getBeanDefinitionNames();
		if (beanNames != null && beanNames.length > 0) {
			for (String beanName : beanNames) {
				if (!(beanName.equals(this.beanName) && beanFactoryToProcess.equals(this.beanFactory))) {

					// XML：resolves '${...}' placeholders within bean definition property values
					BeanDefinition beanDefinition = beanFactoryToProcess.getBeanDefinition(beanName);
					xxlConfDBVisitor.visitBeanDefinition(beanDefinition);


					// Annotation：resolves '@XxlConf' annotations within bean definition fields
					if (beanDefinition.getBeanClassName() == null) {
						continue;
					}
					Class beanClazz = null;
					try {
						beanClazz = Class.forName(beanDefinition.getBeanClassName());
					} catch (ClassNotFoundException e) {
						logger.error(">>>>>>>>>>> xxl-conf, annotation bean class invalid, error msg:{}", e.getMessage());
					}
					if (beanClazz == null) {
						continue;
					}
					final List<Field> annoBeanFields = new ArrayList<>();
					ReflectionUtils.doWithFields(beanClazz, new ReflectionUtils.FieldCallback() {
						@Override
						public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
							if (field.isAnnotationPresent(XxlConf.class)) {
								annoBeanFields.add(field);
							}
						}
					});
					if (annoBeanFields.size() < 1) {
						continue;
					}

					Object beanWithXxlConf = beanFactoryToProcess.getBean(beanName);	// TODO，springboot环境下，通过该方法 "getBean" 获取获取部分Bean，如Spring和Jackson等组件的Bean 会报错。原因未知；
					refreshBeanWithXxlConf(beanWithXxlConf, annoBeanFields);	// refresh bean with xxl conf
				}
			}
		}

		logger.info(">>>>>>>>>>> xxl conf, XxlConfFactory process success");
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	private String beanName;
	@Override
	public void setBeanName(String name) {
		this.beanName = name;
	}

	private BeanFactory beanFactory;
	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setIgnoreUnresolvablePlaceholders(boolean ignoreUnresolvablePlaceholders) {
		super.setIgnoreUnresolvablePlaceholders(true);
	}

}
