/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud;

import static com.google.common.base.MoreObjects.firstNonNull;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.RetrySettings;
import com.google.api.gax.grpc.ChannelProvider;
import com.google.api.gax.grpc.InstantiatingChannelProvider;
import com.google.api.gax.grpc.UnaryCallSettings;
import com.google.auth.Credentials;
import com.google.cloud.spi.ServiceRpcFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;

import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.SharedResourceHolder.Resource;

import org.joda.time.Duration;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Abstract class representing service options for those services that use gRPC as the transport
 * layer.
 *
 * @param <ServiceT> the service subclass
 * @param <ServiceRpcT> the spi-layer class corresponding to the service
 * @param <OptionsT> the {@code ServiceOptions} subclass corresponding to the service
 */
public abstract class GrpcServiceOptions<ServiceT extends Service<OptionsT>, ServiceRpcT,
    OptionsT extends GrpcServiceOptions<ServiceT, ServiceRpcT, OptionsT>>
    extends ServiceOptions<ServiceT, ServiceRpcT, OptionsT> {

  private static final long serialVersionUID = -3093986242928037007L;
  private final String executorFactoryClassName;
  private final int initialTimeout;
  private final double timeoutMultiplier;
  private final int maxTimeout;

  private transient ExecutorFactory<ScheduledExecutorService> executorFactory;

  /**
   * Shared thread pool executor.
   */
  private static final Resource<ScheduledExecutorService> EXECUTOR =
      new Resource<ScheduledExecutorService>() {
        @Override
        public ScheduledExecutorService create() {
          ScheduledThreadPoolExecutor service = new ScheduledThreadPoolExecutor(8);
          service.setKeepAliveTime(5, TimeUnit.SECONDS);
          service.allowCoreThreadTimeOut(true);
          service.setRemoveOnCancelPolicy(true);
          return service;
        }

        @Override
        public void close(ScheduledExecutorService instance) {
          instance.shutdown();
        }
      };

  /**
   * An interface for {@link ExecutorService} factories. Implementations of this interface can be
   * used to provide an user-defined executor to execute requests. Any implementation of this
   * interface must override the {@code get()} method to return the desired executor. The
   * {@code release(executor)} method should be overriden to free resources used by the executor (if
   * needed) according to application's logic.
   *
   * <p>Implementation must provide a public no-arg constructor. Loading of a factory implementation
   * is done via {@link java.util.ServiceLoader}.
   *
   * @param <T> the {@link ExecutorService} subclass created by this factory
   */
  public interface ExecutorFactory<T extends ExecutorService> {

    /**
     * Gets an executor service instance.
     */
    T get();

    /**
     * Releases resources used by the executor and possibly shuts it down.
     */
    void release(T executor);
  }

  @VisibleForTesting
  static class DefaultExecutorFactory implements ExecutorFactory<ScheduledExecutorService> {

    private static final DefaultExecutorFactory INSTANCE = new DefaultExecutorFactory();

    @Override
    public ScheduledExecutorService get() {
      return SharedResourceHolder.get(EXECUTOR);
    }

    @Override
    public synchronized void release(ScheduledExecutorService executor) {
      SharedResourceHolder.release(EXECUTOR, executor);
    }
  }

  /**
   * Builder for {@code GrpcServiceOptions}.
   *
   * @param <ServiceT> the service subclass
   * @param <ServiceRpcT> the spi-layer class corresponding to the service
   * @param <OptionsT> the {@code GrpcServiceOptions} subclass corresponding to the service
   * @param <B> the {@code ServiceOptions} builder
   */
  protected abstract static class Builder<ServiceT extends Service<OptionsT>, ServiceRpcT,
      OptionsT extends GrpcServiceOptions<ServiceT, ServiceRpcT, OptionsT>,
      B extends Builder<ServiceT, ServiceRpcT, OptionsT, B>>
      extends ServiceOptions.Builder<ServiceT, ServiceRpcT, OptionsT, B> {

    private ExecutorFactory executorFactory;
    private int initialTimeout = 20_000;
    private double timeoutMultiplier = 1.5;
    private int maxTimeout = 100_000;

    protected Builder() {}

    protected Builder(GrpcServiceOptions<ServiceT, ServiceRpcT, OptionsT> options) {
      super(options);
      executorFactory = options.executorFactory;
      initialTimeout = options.initialTimeout;
      timeoutMultiplier = options.timeoutMultiplier;
      maxTimeout = options.maxTimeout;
    }

    @Override
    protected abstract GrpcServiceOptions<ServiceT, ServiceRpcT, OptionsT> build();

    /**
     * Sets the scheduled executor factory. This method can be used to provide an user-defined
     * scheduled executor to execute requests.
     *
     * @return the builder
     */
    @Deprecated
    public B executorFactory(ExecutorFactory<ScheduledExecutorService> executorFactory) {
      return setExecutorFactory(executorFactory);
    }

    /**
     * Sets the scheduled executor factory. This method can be used to provide an user-defined
     * scheduled executor to execute requests.
     *
     * @return the builder
     */
    public B setExecutorFactory(ExecutorFactory<ScheduledExecutorService> executorFactory) {
      this.executorFactory = executorFactory;
      return self();
    }

    /**
     * Sets the timeout for the initial RPC, in milliseconds. Subsequent calls will use this value
     * adjusted according to {@link #setTimeoutMultiplier(double)}. Default value is 20000.
     *
     * @return the builder
     * @throws IllegalArgumentException if the provided timeout is &lt; 0
     */
    @Deprecated
    public B initialTimeout(int initialTimeout) {
      return setInitialTimeout(initialTimeout);
    }

    /**
     * Sets the timeout for the initial RPC, in milliseconds. Subsequent calls will use this value
     * adjusted according to {@link #setTimeoutMultiplier(double)}. Default value is 20000.
     *
     * @return the builder
     * @throws IllegalArgumentException if the provided timeout is &lt; 0
     */
    public B setInitialTimeout(int initialTimeout) {
      Preconditions.checkArgument(initialTimeout > 0, "Initial timeout must be > 0");
      this.initialTimeout = initialTimeout;
      return self();
    }

    /**
     * Sets the timeout multiplier. This value is used to compute the timeout for a retried RPC.
     * Timeout is computed as {@code timeoutMultiplier * previousTimeout}. Default value is 1.5.
     *
     * @return the builder
     * @throws IllegalArgumentException if the provided timeout multiplier is &lt; 0
     */
    @Deprecated
    public B timeoutMultiplier(double timeoutMultiplier) {
      return setTimeoutMultiplier(timeoutMultiplier);
    }

    /**
     * Sets the timeout multiplier. This value is used to compute the timeout for a retried RPC.
     * Timeout is computed as {@code timeoutMultiplier * previousTimeout}. Default value is 1.5.
     *
     * @return the builder
     * @throws IllegalArgumentException if the provided timeout multiplier is &lt; 0
     */
    public B setTimeoutMultiplier(double timeoutMultiplier) {
      Preconditions.checkArgument(timeoutMultiplier >= 1.0, "Timeout multiplier must be >= 1");
      this.timeoutMultiplier = timeoutMultiplier;
      return self();
    }

    /**
     * Sets the maximum timeout for a RPC call, in milliseconds. Default value is 100000. If
     * {@code maxTimeout} is lower than the initial timeout the {@link #setInitialTimeout(int)}
     * value is used instead.
     *
     * @return the builder
     */
    @Deprecated
    public B maxTimeout(int maxTimeout) {
      return setMaxTimeout(maxTimeout);
    }

    /**
     * Sets the maximum timeout for a RPC call, in milliseconds. Default value is 100000. If
     * {@code maxTimeout} is lower than the initial timeout the {@link #setInitialTimeout(int)}
     * value is used instead.
     *
     * @return the builder
     */
    public B setMaxTimeout(int maxTimeout) {
      this.maxTimeout = maxTimeout;
      return self();
    }
  }

  @SuppressWarnings("unchecked")
  protected GrpcServiceOptions(
      Class<? extends ServiceFactory<ServiceT, OptionsT>> serviceFactoryClass,
      Class<? extends ServiceRpcFactory<ServiceRpcT, OptionsT>> rpcFactoryClass, Builder<ServiceT,
      ServiceRpcT, OptionsT, ?> builder) {
    super(serviceFactoryClass, rpcFactoryClass, builder);
    executorFactory = firstNonNull(builder.executorFactory,
        getFromServiceLoader(ExecutorFactory.class, DefaultExecutorFactory.INSTANCE));
    executorFactoryClassName = executorFactory.getClass().getName();
    initialTimeout = builder.initialTimeout;
    timeoutMultiplier = builder.timeoutMultiplier;
    maxTimeout = builder.maxTimeout <= initialTimeout ? initialTimeout : builder.maxTimeout;
  }

  /**
   * Returns a scheduled executor service provider.
   */
  @Deprecated
  protected ExecutorFactory<ScheduledExecutorService> executorFactory() {
    return getExecutorFactory();
  }

  /**
   * Returns a scheduled executor service provider.
   */
  protected ExecutorFactory<ScheduledExecutorService> getExecutorFactory() {
    return executorFactory;
  }

  /**
   * Returns a builder for API call settings.
   */
  @Deprecated
  protected UnaryCallSettings.Builder apiCallSettings() {
    return getApiCallSettings();
  }

  /**
   * Returns a builder for API call settings.
   */
  protected UnaryCallSettings.Builder getApiCallSettings() {
    // todo(mziccard): specify timeout these settings:
    // retryParams().retryMaxAttempts(), retryParams().retryMinAttempts()
    final RetrySettings.Builder builder = RetrySettings.newBuilder()
        .setTotalTimeout(Duration.millis(getRetryParams().getTotalRetryPeriodMillis()))
        .setInitialRpcTimeout(Duration.millis(getInitialTimeout()))
        .setRpcTimeoutMultiplier(getTimeoutMultiplier())
        .setMaxRpcTimeout(Duration.millis(getMaxTimeout()))
        .setInitialRetryDelay(Duration.millis(getRetryParams().getInitialRetryDelayMillis()))
        .setRetryDelayMultiplier(getRetryParams().getRetryDelayBackoffFactor())
        .setMaxRetryDelay(Duration.millis(getRetryParams().getMaxRetryDelayMillis()));
    return UnaryCallSettings.newBuilder().setRetrySettingsBuilder(builder);
  }

  /**
   * Returns a channel provider.
   */
  protected ChannelProvider getChannelProvider() {
    HostAndPort hostAndPort = HostAndPort.fromString(getHost());
    InstantiatingChannelProvider.Builder builder = InstantiatingChannelProvider.newBuilder()
        .setServiceAddress(hostAndPort.getHostText())
        .setPort(hostAndPort.getPort())
        .setClientLibHeader(getLibraryName(), firstNonNull(getLibraryVersion(), ""));
    Credentials scopedCredentials = getScopedCredentials();
    if (scopedCredentials != null && scopedCredentials != NoCredentials.getInstance()) {
      builder.setCredentialsProvider(FixedCredentialsProvider.create(scopedCredentials));
    }
    return builder.build();
  }

  /**
   * Returns the timeout for the initial RPC, in milliseconds. Subsequent calls will use this value
   * adjusted according to {@link #getTimeoutMultiplier()}. Default value is 20000.
   */
  @Deprecated
  public int initialTimeout() {
    return getInitialTimeout();
  }

  /**
   * Returns the timeout for the initial RPC, in milliseconds. Subsequent calls will use this value
   * adjusted according to {@link #getTimeoutMultiplier()}. Default value is 20000.
   */
  public int getInitialTimeout() {
    return initialTimeout;
  }

  /**
   * Returns the timeout multiplier. This values is used to compute the timeout for a RPC. Timeout
   * is computed as {@code timeoutMultiplier * previousTimeout}. Default value is 1.5.
   */
  @Deprecated
  public double timeoutMultiplier() {
    return getTimeoutMultiplier();
  }

  /**
   * Returns the timeout multiplier. This values is used to compute the timeout for a RPC. Timeout
   * is computed as {@code timeoutMultiplier * previousTimeout}. Default value is 1.5.
   */
  public double getTimeoutMultiplier() {
    return timeoutMultiplier;
  }

  /**
   * Returns the maximum timeout for a RPC call, in milliseconds. Default value is 100000.
   */
  @Deprecated
  public int maxTimeout() {
    return getMaxTimeout();
  }

  /**
   * Returns the maximum timeout for a RPC call, in milliseconds. Default value is 100000.
   */
  public int getMaxTimeout() {
    return maxTimeout;
  }

  @Override
  protected int baseHashCode() {
    return Objects.hash(super.baseHashCode(), executorFactoryClassName, initialTimeout,
        timeoutMultiplier, maxTimeout);
  }

  protected boolean baseEquals(GrpcServiceOptions<?, ?, ?> other) {
    return super.baseEquals(other)
        && Objects.equals(executorFactoryClassName, other.executorFactoryClassName)
        && Objects.equals(initialTimeout, other.initialTimeout)
        && Objects.equals(timeoutMultiplier, other.timeoutMultiplier)
        && Objects.equals(maxTimeout, other.maxTimeout);
  }

  private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
    input.defaultReadObject();
    executorFactory = newInstance(executorFactoryClassName);
  }
}
