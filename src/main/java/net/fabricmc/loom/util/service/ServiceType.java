/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024-2025 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;

/**
 * A record to hold the options and service class for a service.
 * @param optionsClass The options class for the service.
 * @param serviceClass The service class.
 */
public record ServiceType<O extends Service.Options, S extends Service<O>>(Class<O> optionsClass, Class<S> serviceClass) {
	/**
	 * Create an instance of the options class for the given service class.
	 * @param project The {@link Project} to create the options for.
	 * @param action An action to configure the options.
	 * @return The created options provider.
	 */
	public Provider<O> create(Project project, Action<O> action) {
		return maybeCreate(project, o -> {
			action.execute(o);
			return true;
		});
	}

	/**
	 * Maybe create an instance of the options class for the given service class.
	 * @param project The {@link Project} to create the options for.
	 * @param function A function to configure the options, returning true if the options should be created.
	 * @return The created options provider.
	 */
	public Provider<O> maybeCreate(Project project, Function<O, Boolean> function) {
		return project.provider(() -> {
			O options = project.getObjects().newInstance(optionsClass);

			for (Method method : optionsClass.getDeclaredMethods()) {
				// Gradle property values are lazily initialized, ensure that all of the values are not null
				// Before we try to serialize the options as json
				method.invoke(options);
			}

			options.getServiceClass().set(serviceClass.getName());
			options.getServiceClass().finalizeValue();

			if (function.apply(options)) {
				return options;
			}

			return null;
		});
	}

	/**
	 * 创建一个只依赖 {@link ObjectFactory} 的选项实例.
	 *
	 * <p>与 {@link #create(Project, Action)} 的唯一区别是不需要 {@code Project}，因此可以在执行期使用：
	 * 由任务注入的 {@link ObjectFactory} 实例化选项对象。选项里那些只能配置期取得的值（例如解析好的
	 * 依赖文件）由调用方先行解析成纯值，再在 {@code action} 里填进去。
	 *
	 * @param objects 执行期可用的对象工厂
	 * @param type 选项与服务的类型
	 * @param action 配置选项的动作
	 * @param <O> 选项类型
	 * @return 创建好的选项实例
	 */
	public static <O extends Service.Options> O createOptions(ObjectFactory objects, ServiceType<O, ?> type, Action<O> action) {
		final O options = objects.newInstance(type.optionsClass());

		for (Method method : type.optionsClass().getDeclaredMethods()) {
			// 与 maybeCreate 同理：属性是惰性初始化的，必须在这里都取一遍，
			// 否则 JSON 序列化得到的选项里这些字段为 null
			try {
				method.invoke(options);
			} catch (IllegalAccessException | InvocationTargetException e) {
				throw new RuntimeException("Failed to initialize the options of " + type.optionsClass().getName(), e);
			}
		}

		options.getServiceClass().set(type.serviceClass().getName());
		options.getServiceClass().finalizeValue();
		action.execute(options);
		return options;
	}
}
