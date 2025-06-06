/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.environment;

import org.apache.flink.annotation.Experimental;
import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.Public;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.InvalidProgramException;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.cache.DistributedCache;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.InvalidTypesException;
import org.apache.flink.api.common.io.FileInputFormat;
import org.apache.flink.api.common.io.FilePathFilter;
import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.api.common.operators.ResourceSpec;
import org.apache.flink.api.common.operators.SlotSharingGroup;
import org.apache.flink.api.common.operators.util.SlotSharingGroupUtils;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.lib.NumberSequenceSource;
import org.apache.flink.api.connector.source.util.ratelimit.RateLimiterStrategy;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.api.java.ClosureCleaner;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.typeutils.MissingTypeInfo;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.StateChangelogOptions;
import org.apache.flink.connector.datagen.functions.FromElementsGeneratorFunction;
import org.apache.flink.connector.datagen.source.DataGeneratorSource;
import org.apache.flink.core.execution.CacheSupportedPipelineExecutor;
import org.apache.flink.core.execution.CheckpointingMode;
import org.apache.flink.core.execution.DefaultExecutorServiceLoader;
import org.apache.flink.core.execution.DetachedJobExecutionResult;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.core.execution.JobListener;
import org.apache.flink.core.execution.PipelineExecutor;
import org.apache.flink.core.execution.PipelineExecutorFactory;
import org.apache.flink.core.execution.PipelineExecutorServiceLoader;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.scheduler.ClusterDatasetCorruptedException;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.functions.source.ContinuousFileReaderOperatorFactory;
import org.apache.flink.streaming.api.functions.source.FileProcessingMode;
import org.apache.flink.streaming.api.functions.source.TimestampedFileInputSplit;
import org.apache.flink.streaming.api.functions.source.legacy.ContinuousFileMonitoringFunction;
import org.apache.flink.streaming.api.functions.source.legacy.FileMonitoringFunction;
import org.apache.flink.streaming.api.functions.source.legacy.FileReadFunction;
import org.apache.flink.streaming.api.functions.source.legacy.FromElementsFunction;
import org.apache.flink.streaming.api.functions.source.legacy.FromIteratorFunction;
import org.apache.flink.streaming.api.functions.source.legacy.FromSplittableIteratorFunction;
import org.apache.flink.streaming.api.functions.source.legacy.InputFormatSourceFunction;
import org.apache.flink.streaming.api.functions.source.legacy.ParallelSourceFunction;
import org.apache.flink.streaming.api.functions.source.legacy.RichParallelSourceFunction;
import org.apache.flink.streaming.api.functions.source.legacy.SocketTextStreamFunction;
import org.apache.flink.streaming.api.functions.source.legacy.SourceFunction;
import org.apache.flink.streaming.api.functions.source.legacy.StatefulSequenceSource;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.streaming.api.graph.StreamGraphGenerator;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.api.operators.collect.CollectResultIterator;
import org.apache.flink.streaming.api.transformations.CacheTransformation;
import org.apache.flink.util.AbstractID;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.SplittableIterator;
import org.apache.flink.util.StringUtils;
import org.apache.flink.util.TernaryBoolean;
import org.apache.flink.util.Utils;
import org.apache.flink.util.WrappingRuntimeException;

import javax.annotation.Nullable;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * The StreamExecutionEnvironment is the context in which a streaming program is executed. A {@link
 * LocalStreamEnvironment} will cause execution in the current JVM, a {@link
 * RemoteStreamEnvironment} will cause execution on a remote setup.
 *
 * <p>The environment provides methods to control the job execution (such as setting the parallelism
 * or the fault tolerance/checkpointing parameters) and to interact with the outside world (data
 * access).
 *
 * @see org.apache.flink.streaming.api.environment.LocalStreamEnvironment
 * @see org.apache.flink.streaming.api.environment.RemoteStreamEnvironment
 */
@Public
public class StreamExecutionEnvironment implements AutoCloseable {

    private final List<CollectResultIterator<?>> collectIterators = new ArrayList<>();

    @Internal
    public void registerCollectIterator(CollectResultIterator<?> iterator) {
        collectIterators.add(iterator);
    }

    /**
     * The environment of the context (local by default, cluster if invoked through command line).
     */
    private static StreamExecutionEnvironmentFactory contextEnvironmentFactory = null;

    /** The ThreadLocal used to store {@link StreamExecutionEnvironmentFactory}. */
    private static final ThreadLocal<StreamExecutionEnvironmentFactory>
            threadLocalContextEnvironmentFactory = new ThreadLocal<>();

    /** The default parallelism used when creating a local environment. */
    private static int defaultLocalParallelism = Runtime.getRuntime().availableProcessors();

    // ------------------------------------------------------------------------

    /** The execution configuration for this environment. */
    protected final ExecutionConfig config;

    /** Settings that control the checkpointing behavior. */
    protected final CheckpointConfig checkpointCfg;

    protected final List<Transformation<?>> transformations = new ArrayList<>();

    private final Map<AbstractID, CacheTransformation<?>> cachedTransformations = new HashMap<>();

    /**
     * Now we could not migrate this field to configuration. Because this object field remains
     * directly accessible and modifiable as it is exposed through a getter to users, allowing
     * external modifications.
     */
    protected final List<Tuple2<String, DistributedCache.DistributedCacheEntry>> cacheFile =
            new ArrayList<>();

    private final PipelineExecutorServiceLoader executorServiceLoader;

    /**
     * Currently, configuration is split across multiple member variables and classes such as {@link
     * ExecutionConfig} or {@link CheckpointConfig}. This architecture makes it quite difficult to
     * handle/merge/enrich configuration or restrict access in other APIs.
     *
     * <p>In the long-term, this {@link Configuration} object should be the source of truth for
     * newly added {@link ConfigOption}s that are relevant for DataStream API. Make sure to also
     * update {@link #configure(ReadableConfig, ClassLoader)}.
     */
    protected final Configuration configuration;

    private final ClassLoader userClassloader;

    private final List<JobListener> jobListeners = new ArrayList<>();

    // Records the slot sharing groups and their corresponding fine-grained ResourceProfile
    private final Map<String, ResourceProfile> slotSharingGroupResources = new HashMap<>();

    // --------------------------------------------------------------------------------------------
    // Constructor and Properties
    // --------------------------------------------------------------------------------------------

    public StreamExecutionEnvironment() {
        this(new Configuration());
        // unfortunately, StreamExecutionEnvironment always (implicitly) had a public constructor.
        // This constructor is not useful because the execution environment cannot be used for
        // execution. We're keeping this to appease the binary compatibiliy checks.
    }

    /**
     * Creates a new {@link StreamExecutionEnvironment} that will use the given {@link
     * Configuration} to configure the {@link PipelineExecutor}.
     */
    @PublicEvolving
    public StreamExecutionEnvironment(final Configuration configuration) {
        this(configuration, null);
    }

    /**
     * Creates a new {@link StreamExecutionEnvironment} that will use the given {@link
     * Configuration} to configure the {@link PipelineExecutor}.
     *
     * <p>In addition, this constructor allows specifying the user code {@link ClassLoader}.
     */
    @PublicEvolving
    public StreamExecutionEnvironment(
            final Configuration configuration, final ClassLoader userClassloader) {
        this(new DefaultExecutorServiceLoader(), configuration, userClassloader);
    }

    /**
     * Creates a new {@link StreamExecutionEnvironment} that will use the given {@link
     * Configuration} to configure the {@link PipelineExecutor}.
     *
     * <p>In addition, this constructor allows specifying the {@link PipelineExecutorServiceLoader}
     * and user code {@link ClassLoader}.
     */
    @PublicEvolving
    public StreamExecutionEnvironment(
            final PipelineExecutorServiceLoader executorServiceLoader,
            final Configuration configuration,
            final ClassLoader userClassloader) {
        this.executorServiceLoader = checkNotNull(executorServiceLoader);
        this.configuration = new Configuration(checkNotNull(configuration));
        this.config = new ExecutionConfig(this.configuration);
        this.checkpointCfg = new CheckpointConfig(this.configuration);
        this.userClassloader =
                userClassloader == null ? getClass().getClassLoader() : userClassloader;

        // the configuration of a job or an operator can be specified at the following places:
        //     i) at the operator level via e.g. parallelism by using the
        // SingleOutputStreamOperator.setParallelism().
        //     ii) programmatically by using e.g. the env.setRestartStrategy() method
        //     iii) in the configuration passed here
        //
        // if specified in multiple places, the priority order is the above.
        //
        // Given this, it is safe to overwrite the execution config default values here because all
        // other ways assume
        // that the env is already instantiated so they will overwrite the value passed here.
        this.configure(this.configuration, this.userClassloader);
    }

    protected ClassLoader getUserClassloader() {
        return userClassloader;
    }

    /** Gets the config object. */
    public ExecutionConfig getConfig() {
        return config;
    }

    /**
     * Get the list of cached files that were registered for distribution among the task managers.
     */
    public List<Tuple2<String, DistributedCache.DistributedCacheEntry>> getCachedFiles() {
        return cacheFile;
    }

    /** Gets the config JobListeners. */
    @PublicEvolving
    public List<JobListener> getJobListeners() {
        return jobListeners;
    }

    /**
     * Sets the parallelism for operations executed through this environment. Setting a parallelism
     * of x here will cause all operators (such as map, batchReduce) to run with x parallel
     * instances. This method overrides the default parallelism for this environment. The {@link
     * LocalStreamEnvironment} uses by default a value equal to the number of hardware contexts (CPU
     * cores / threads). When executing the program via the command line client from a JAR file, the
     * default degree of parallelism is the one configured for that setup.
     *
     * @param parallelism The parallelism
     */
    public StreamExecutionEnvironment setParallelism(int parallelism) {
        config.setParallelism(parallelism);
        return this;
    }

    /**
     * Sets the runtime execution mode for the application (see {@link RuntimeExecutionMode}). This
     * is equivalent to setting the {@code execution.runtime-mode} in your application's
     * configuration file.
     *
     * <p>We recommend users to NOT use this method but set the {@code execution.runtime-mode} using
     * the command-line when submitting the application. Keeping the application code
     * configuration-free allows for more flexibility as the same application will be able to be
     * executed in any execution mode.
     *
     * @param executionMode the desired execution mode.
     * @return The execution environment of your application.
     */
    @PublicEvolving
    public StreamExecutionEnvironment setRuntimeMode(final RuntimeExecutionMode executionMode) {
        checkNotNull(executionMode);
        configuration.set(ExecutionOptions.RUNTIME_MODE, executionMode);
        return this;
    }

    /**
     * Sets the maximum degree of parallelism defined for the program. The upper limit (inclusive)
     * is Short.MAX_VALUE + 1.
     *
     * <p>The maximum degree of parallelism specifies the upper limit for dynamic scaling. It also
     * defines the number of key groups used for partitioned state.
     *
     * @param maxParallelism Maximum degree of parallelism to be used for the program., with {@code
     *     0 < maxParallelism <= 2^15}.
     */
    public StreamExecutionEnvironment setMaxParallelism(int maxParallelism) {
        Preconditions.checkArgument(
                maxParallelism > 0
                        && maxParallelism <= KeyGroupRangeAssignment.UPPER_BOUND_MAX_PARALLELISM,
                "maxParallelism is out of bounds 0 < maxParallelism <= "
                        + KeyGroupRangeAssignment.UPPER_BOUND_MAX_PARALLELISM
                        + ". Found: "
                        + maxParallelism);

        config.setMaxParallelism(maxParallelism);
        return this;
    }

    /**
     * Register a slot sharing group with its resource spec.
     *
     * <p>Note that a slot sharing group hints the scheduler that the grouped operators CAN be
     * deployed into a shared slot. There's no guarantee that the scheduler always deploy the
     * grouped operators together. In cases grouped operators are deployed into separate slots, the
     * slot resources will be derived from the specified group requirements.
     *
     * @param slotSharingGroup which contains name and its resource spec.
     */
    @PublicEvolving
    public StreamExecutionEnvironment registerSlotSharingGroup(SlotSharingGroup slotSharingGroup) {
        final ResourceSpec resourceSpec =
                SlotSharingGroupUtils.extractResourceSpec(slotSharingGroup);
        if (!resourceSpec.equals(ResourceSpec.UNKNOWN)) {
            this.slotSharingGroupResources.put(
                    slotSharingGroup.getName(),
                    ResourceProfile.fromResourceSpec(resourceSpec, MemorySize.ZERO));
        }
        return this;
    }

    /**
     * Gets the parallelism with which operation are executed by default. Operations can
     * individually override this value to use a specific parallelism.
     *
     * @return The parallelism used by operations, unless they override that value.
     */
    public int getParallelism() {
        return config.getParallelism();
    }

    /**
     * Gets the maximum degree of parallelism defined for the program.
     *
     * <p>The maximum degree of parallelism specifies the upper limit for dynamic scaling. It also
     * defines the number of key groups used for partitioned state.
     *
     * @return Maximum degree of parallelism
     */
    public int getMaxParallelism() {
        return config.getMaxParallelism();
    }

    /**
     * Sets the maximum time frequency (milliseconds) for the flushing of the output buffers. By
     * default the output buffers flush frequently to provide low latency and to aid smooth
     * developer experience. Setting the parameter can result in three logical modes:
     *
     * <ul>
     *   <li>A positive integer triggers flushing periodically by that integer
     *   <li>0 triggers flushing after every record thus minimizing latency
     *   <li>-1 triggers flushing only when the output buffer is full thus maximizing throughput
     * </ul>
     *
     * @param timeoutMillis The maximum time between two output flushes.
     */
    public StreamExecutionEnvironment setBufferTimeout(long timeoutMillis) {
        if (timeoutMillis < ExecutionOptions.DISABLED_NETWORK_BUFFER_TIMEOUT) {
            throw new IllegalArgumentException("Timeout of buffer must be non-negative or -1");
        }

        if (timeoutMillis == ExecutionOptions.DISABLED_NETWORK_BUFFER_TIMEOUT) {
            this.configuration.set(ExecutionOptions.BUFFER_TIMEOUT_ENABLED, false);
        } else {
            this.configuration.set(
                    ExecutionOptions.BUFFER_TIMEOUT, Duration.ofMillis(timeoutMillis));
        }
        return this;
    }

    /**
     * Gets the maximum time frequency (milliseconds) for the flushing of the output buffers. For
     * clarification on the extremal values see {@link #setBufferTimeout(long)}.
     *
     * @return The timeout of the buffer.
     */
    public long getBufferTimeout() {
        return this.configuration.get(ExecutionOptions.BUFFER_TIMEOUT_ENABLED)
                ? this.configuration.get(ExecutionOptions.BUFFER_TIMEOUT).toMillis()
                : ExecutionOptions.DISABLED_NETWORK_BUFFER_TIMEOUT;
    }

    /**
     * Disables operator chaining for streaming operators. Operator chaining allows non-shuffle
     * operations to be co-located in the same thread fully avoiding serialization and
     * de-serialization.
     *
     * @return StreamExecutionEnvironment with chaining disabled.
     */
    @PublicEvolving
    public StreamExecutionEnvironment disableOperatorChaining() {
        this.configuration.set(PipelineOptions.OPERATOR_CHAINING, false);
        return this;
    }

    /**
     * Returns whether operator chaining is enabled.
     *
     * @return {@code true} if chaining is enabled, false otherwise.
     */
    @PublicEvolving
    public boolean isChainingEnabled() {
        return this.configuration.get(PipelineOptions.OPERATOR_CHAINING);
    }

    @PublicEvolving
    public boolean isChainingOfOperatorsWithDifferentMaxParallelismEnabled() {
        return this.configuration.get(
                PipelineOptions.OPERATOR_CHAINING_CHAIN_OPERATORS_WITH_DIFFERENT_MAX_PARALLELISM);
    }

    // ------------------------------------------------------------------------
    //  Checkpointing Settings
    // ------------------------------------------------------------------------

    /**
     * Gets the checkpoint config, which defines values like checkpoint interval, delay between
     * checkpoints, etc.
     *
     * @return The checkpoint config.
     */
    public CheckpointConfig getCheckpointConfig() {
        return checkpointCfg;
    }

    /**
     * Enables checkpointing for the streaming job. The distributed state of the streaming dataflow
     * will be periodically snapshotted. In case of a failure, the streaming dataflow will be
     * restarted from the latest completed checkpoint. This method selects {@link
     * CheckpointingMode#EXACTLY_ONCE} guarantees.
     *
     * <p>The job draws checkpoints periodically, in the given interval. The state will be stored in
     * the configured state backend.
     *
     * <p>NOTE: Checkpointing iterative streaming dataflows is not properly supported at the moment.
     * For that reason, iterative jobs will not be started if used with enabled checkpointing.
     *
     * @param interval Time interval between state checkpoints in milliseconds.
     */
    public StreamExecutionEnvironment enableCheckpointing(long interval) {
        checkpointCfg.setCheckpointInterval(interval);
        return this;
    }

    /**
     * Enables checkpointing for the streaming job. The distributed state of the streaming dataflow
     * will be periodically snapshotted. In case of a failure, the streaming dataflow will be
     * restarted from the latest completed checkpoint.
     *
     * <p>The job draws checkpoints periodically, in the given interval. The system uses the given
     * {@link org.apache.flink.streaming.api.CheckpointingMode} for the checkpointing ("exactly
     * once" vs "at least once"). The state will be stored in the configured state backend.
     *
     * <p>NOTE: Checkpointing iterative streaming dataflows is not properly supported at the moment.
     * For that reason, iterative jobs will not be started if used with enabled checkpointing.
     *
     * @param interval Time interval between state checkpoints in milliseconds.
     * @param mode The checkpointing mode, selecting between "exactly once" and "at least once"
     *     guaranteed.
     * @deprecated use {@link #enableCheckpointing(long, CheckpointingMode)} instead.
     */
    @Deprecated
    public StreamExecutionEnvironment enableCheckpointing(
            long interval, org.apache.flink.streaming.api.CheckpointingMode mode) {
        checkpointCfg.setCheckpointingMode(mode);
        checkpointCfg.setCheckpointInterval(interval);
        return this;
    }

    /**
     * Enables checkpointing for the streaming job. The distributed state of the streaming dataflow
     * will be periodically snapshotted. In case of a failure, the streaming dataflow will be
     * restarted from the latest completed checkpoint.
     *
     * <p>The job draws checkpoints periodically, in the given interval. The system uses the given
     * {@link CheckpointingMode} for the checkpointing ("exactly once" vs "at least once"). The
     * state will be stored in the configured state backend.
     *
     * <p>NOTE: Checkpointing iterative streaming dataflows is not properly supported at the moment.
     * For that reason, iterative jobs will not be started if used with enabled checkpointing.
     *
     * @param interval Time interval between state checkpoints in milliseconds.
     * @param mode The checkpointing mode, selecting between "exactly once" and "at least once"
     *     guaranteed.
     */
    public StreamExecutionEnvironment enableCheckpointing(long interval, CheckpointingMode mode) {
        checkpointCfg.setCheckpointingConsistencyMode(mode);
        checkpointCfg.setCheckpointInterval(interval);
        return this;
    }

    /**
     * Returns the checkpointing interval or -1 if checkpointing is disabled.
     *
     * <p>Shorthand for {@code getCheckpointConfig().getCheckpointInterval()}.
     *
     * @return The checkpointing interval or -1
     */
    public long getCheckpointInterval() {
        return checkpointCfg.getCheckpointInterval();
    }

    /** Returns whether unaligned checkpoints are enabled. */
    @PublicEvolving
    public boolean isUnalignedCheckpointsEnabled() {
        return checkpointCfg.isUnalignedCheckpointsEnabled();
    }

    /** Returns whether unaligned checkpoints are force-enabled. */
    @PublicEvolving
    public boolean isForceUnalignedCheckpoints() {
        return checkpointCfg.isForceUnalignedCheckpoints();
    }

    /**
     * Returns the checkpointing mode (exactly-once vs. at-least-once).
     *
     * <p>Shorthand for {@code getCheckpointConfig().getCheckpointingMode()}.
     *
     * @return The checkpoint mode
     * @deprecated Use {@link #getCheckpointingConsistencyMode()} instead.
     */
    @Deprecated
    public org.apache.flink.streaming.api.CheckpointingMode getCheckpointingMode() {
        return checkpointCfg.getCheckpointingMode();
    }

    /**
     * Returns the checkpointing consistency mode (exactly-once vs. at-least-once).
     *
     * <p>Shorthand for {@code getCheckpointConfig().getCheckpointingConsistencyMode()}.
     *
     * @return The checkpoint mode
     */
    public CheckpointingMode getCheckpointingConsistencyMode() {
        return checkpointCfg.getCheckpointingConsistencyMode();
    }

    /**
     * Enable the change log for current state backend. This change log allows operators to persist
     * state changes in a very fine-grained manner. Currently, the change log only applies to keyed
     * state, so non-keyed operator state and channel state are persisted as usual. The 'state' here
     * refers to 'keyed state'. Details are as follows:
     *
     * <p>Stateful operators write the state changes to that log (logging the state), in addition to
     * applying them to the state tables in RocksDB or the in-mem Hashtable.
     *
     * <p>An operator can acknowledge a checkpoint as soon as the changes in the log have reached
     * the durable checkpoint storage.
     *
     * <p>The state tables are persisted periodically, independent of the checkpoints. We call this
     * the materialization of the state on the checkpoint storage.
     *
     * <p>Once the state is materialized on checkpoint storage, the state changelog can be truncated
     * to the corresponding point.
     *
     * <p>It establish a way to drastically reduce the checkpoint interval for streaming
     * applications across state backends. For more details please check the FLIP-158.
     *
     * <p>If this method is not called explicitly, it means no preference for enabling the change
     * log. Configs for change log enabling will override in different config levels
     * (job/local/cluster).
     *
     * @param enabled true if enable the change log for state backend explicitly, otherwise disable
     *     the change log.
     * @return This StreamExecutionEnvironment itself, to allow chaining of function calls.
     * @see #isChangelogStateBackendEnabled()
     */
    @PublicEvolving
    public StreamExecutionEnvironment enableChangelogStateBackend(boolean enabled) {
        configuration.set(StateChangelogOptions.ENABLE_STATE_CHANGE_LOG, enabled);
        return this;
    }

    /**
     * Gets the enable status of change log for state backend.
     *
     * @return a {@link TernaryBoolean} for the enable status of change log for state backend. Could
     *     be {@link TernaryBoolean#UNDEFINED} if user never specify this by calling {@link
     *     #enableChangelogStateBackend(boolean)}.
     * @see #enableChangelogStateBackend(boolean)
     */
    @PublicEvolving
    public TernaryBoolean isChangelogStateBackendEnabled() {
        return this.configuration
                .getOptional(StateChangelogOptions.ENABLE_STATE_CHANGE_LOG)
                .map(TernaryBoolean::fromBoolean)
                .orElse(TernaryBoolean.UNDEFINED);
    }

    /**
     * Sets the default savepoint directory, where savepoints will be written to if no is explicitly
     * provided when triggered.
     *
     * @return This StreamExecutionEnvironment itself, to allow chaining of function calls.
     * @see #getDefaultSavepointDirectory()
     */
    @PublicEvolving
    public StreamExecutionEnvironment setDefaultSavepointDirectory(String savepointDirectory) {
        this.configuration.set(
                CheckpointingOptions.SAVEPOINT_DIRECTORY,
                Preconditions.checkNotNull(savepointDirectory));
        return this;
    }

    /**
     * Sets the default savepoint directory, where savepoints will be written to if no is explicitly
     * provided when triggered.
     *
     * @return This StreamExecutionEnvironment itself, to allow chaining of function calls.
     * @see #getDefaultSavepointDirectory()
     */
    @PublicEvolving
    public StreamExecutionEnvironment setDefaultSavepointDirectory(URI savepointDirectory) {
        Preconditions.checkNotNull(savepointDirectory);
        return setDefaultSavepointDirectory(savepointDirectory.getPath());
    }

    /**
     * Sets the default savepoint directory, where savepoints will be written to if no is explicitly
     * provided when triggered.
     *
     * @return This StreamExecutionEnvironment itself, to allow chaining of function calls.
     * @see #getDefaultSavepointDirectory()
     */
    @PublicEvolving
    public StreamExecutionEnvironment setDefaultSavepointDirectory(Path savepointDirectory) {
        Preconditions.checkNotNull(savepointDirectory);
        setDefaultSavepointDirectory(savepointDirectory.getPath());
        return this;
    }

    /**
     * Gets the default savepoint directory for this Job.
     *
     * @see #setDefaultSavepointDirectory(Path)
     */
    @Nullable
    @PublicEvolving
    public Path getDefaultSavepointDirectory() {
        String path = this.configuration.get(CheckpointingOptions.SAVEPOINT_DIRECTORY);
        return path == null ? null : new Path(path);
    }

    // --------------------------------------------------------------------------------------------
    //  Time characteristic
    // --------------------------------------------------------------------------------------------

    /**
     * Sets all relevant options contained in the {@link ReadableConfig}. It will reconfigure {@link
     * StreamExecutionEnvironment}, {@link ExecutionConfig} and {@link CheckpointConfig}.
     *
     * <p>It will change the value of a setting only if a corresponding option was set in the {@code
     * configuration}. If a key is not present, the current value of a field will remain untouched.
     *
     * @param configuration a configuration to read the values from
     */
    @PublicEvolving
    public void configure(ReadableConfig configuration) {
        configure(configuration, userClassloader);
    }

    /**
     * Sets all relevant options contained in the {@link ReadableConfig}. It will reconfigure {@link
     * StreamExecutionEnvironment}, {@link ExecutionConfig} and {@link CheckpointConfig}.
     *
     * <p>It will change the value of a setting only if a corresponding option was set in the {@code
     * configuration}. If a key is not present, the current value of a field will remain untouched.
     *
     * @param configuration a configuration to read the values from
     * @param classLoader a class loader to use when loading classes
     */
    @PublicEvolving
    public void configure(ReadableConfig configuration, ClassLoader classLoader) {
        this.configuration.addAll(Configuration.fromMap(configuration.toMap()));
        configuration
                .getOptional(DeploymentOptions.JOB_LISTENERS)
                .ifPresent(listeners -> registerCustomListeners(classLoader, listeners));
        configuration
                .getOptional(PipelineOptions.CACHED_FILES)
                .ifPresent(
                        f -> {
                            this.cacheFile.clear();
                            this.cacheFile.addAll(DistributedCache.parseCachedFilesFromString(f));
                        });

        config.configure(configuration, classLoader);
        checkpointCfg.configure(configuration);
    }

    private void registerCustomListeners(
            final ClassLoader classLoader, final List<String> listeners) {
        for (String listener : listeners) {
            try {
                final JobListener jobListener =
                        InstantiationUtil.instantiate(listener, JobListener.class, classLoader);
                jobListeners.add(jobListener);
            } catch (FlinkException e) {
                throw new WrappingRuntimeException("Could not load JobListener : " + listener, e);
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // Data stream creations
    // --------------------------------------------------------------------------------------------

    /**
     * Creates a new data stream that contains the given elements. The elements must all be of the
     * same type, for example, all of the {@link String} or {@link Integer}.
     *
     * <p>The framework will try and determine the exact type from the elements. In case of generic
     * elements, it may be necessary to manually supply the type information via {@link
     * #fromData(org.apache.flink.api.common.typeinfo.TypeInformation, OUT...)}.
     *
     * <p>NOTE: This creates a non-parallel data stream source by default (parallelism of one).
     * Adjustment of parallelism is supported via {@code setParallelism()} on the result.
     *
     * @param data The array of elements to create the data stream from.
     * @param <OUT> The type of the returned data stream
     * @return The data stream representing the given array of elements
     */
    @SafeVarargs
    public final <OUT> DataStreamSource<OUT> fromData(OUT... data) {
        if (data.length == 0) {
            throw new IllegalArgumentException(
                    "fromElements needs at least one element as argument");
        }

        TypeInformation<OUT> typeInfo;
        try {
            typeInfo = TypeExtractor.getForObject(data[0]);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Could not create TypeInformation for type "
                            + data[0].getClass().getName()
                            + "; please specify the TypeInformation manually via "
                            + "StreamExecutionEnvironment#fromData(Collection, TypeInformation)",
                    e);
        }
        return fromData(Arrays.asList(data), typeInfo);
    }

    /**
     * Creates a new data stream that contains the given elements. The elements should be the same
     * or be the subclass to the {@code typeInfo} type. The sequence of elements must not be empty.
     *
     * <p>NOTE: This creates a non-parallel data stream source by default (parallelism of one).
     * Adjustment of parallelism is supported via {@code setParallelism()} on the result.
     *
     * @param typeInfo The type information of the elements.
     * @param data The array of elements to create the data stream from.
     * @param <OUT> The type of the returned data stream
     * @return The data stream representing the given array of elements
     */
    @SafeVarargs
    public final <OUT> DataStreamSource<OUT> fromData(TypeInformation<OUT> typeInfo, OUT... data) {
        if (data.length == 0) {
            throw new IllegalArgumentException(
                    "fromElements needs at least one element as argument");
        }
        return fromData(Arrays.asList(data), typeInfo);
    }

    /**
     * Creates a new data stream that contains the given elements. The elements must all be of the
     * same type, for example, all of the {@link String} or {@link Integer}.
     *
     * <p>The framework will try and determine the exact type from the elements. In case of generic
     * elements, it may be necessary to manually supply the type information via {@link
     * #fromData(org.apache.flink.api.common.typeinfo.TypeInformation, OUT...)}.
     *
     * <p>NOTE: This creates a non-parallel data stream source by default (parallelism of one).
     * Adjustment of parallelism is supported via {@code setParallelism()} on the result.
     *
     * @param data The collection of elements to create the data stream from.
     * @param typeInfo The type information of the elements.
     * @param <OUT> The generic type of the returned data stream.
     * @return The data stream representing the given collection
     */
    public <OUT> DataStreamSource<OUT> fromData(
            Collection<OUT> data, TypeInformation<OUT> typeInfo) {
        Preconditions.checkNotNull(data, "Collection must not be null");

        FromElementsGeneratorFunction<OUT> generatorFunction =
                new FromElementsGeneratorFunction<>(typeInfo, getConfig(), data);

        DataGeneratorSource<OUT> generatorSource =
                new DataGeneratorSource<>(generatorFunction, data.size(), typeInfo);

        return fromSource(
                        generatorSource,
                        WatermarkStrategy.forMonotonousTimestamps(),
                        "Collection Source")
                .setParallelism(1);
    }

    /**
     * Creates a new data stream that contains the given elements. The framework will determine the
     * type according to the based type user supplied. The elements should be the same or be the
     * subclass to the based type. The sequence of elements must not be empty.
     *
     * <p>NOTE: This creates a non-parallel data stream source by default (parallelism of one).
     * Adjustment of parallelism is supported via {@code setParallelism()} on the result.
     *
     * @param type The based class type in the collection.
     * @param data The array of elements to create the data stream from.
     * @param <OUT> The type of the returned data stream
     * @return The data stream representing the given array of elements
     */
    @SafeVarargs
    public final <OUT> DataStreamSource<OUT> fromData(Class<OUT> type, OUT... data) {
        if (data.length == 0) {
            throw new IllegalArgumentException(
                    "fromElements needs at least one element as argument");
        }

        TypeInformation<OUT> typeInfo;
        try {
            typeInfo = TypeExtractor.getForClass(type);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Could not create TypeInformation for type "
                            + type.getName()
                            + "; please specify the TypeInformation manually via "
                            + "StreamExecutionEnvironment#fromData(Collection, TypeInformation)",
                    e);
        }
        return fromData(Arrays.asList(data), typeInfo);
    }

    /**
     * Creates a new data stream that contains the given elements.The type of the data stream is
     * that of the elements in the collection.
     *
     * <p>The framework will try and determine the exact type from the collection elements. In case
     * of generic elements, it may be necessary to manually supply the type information via {@link
     * #fromData(java.util.Collection, org.apache.flink.api.common.typeinfo.TypeInformation)}.
     *
     * <p>NOTE: This creates a non-parallel data stream source by default (parallelism of one).
     * Adjustment of parallelism is supported via {@code setParallelism()} on the result.
     *
     * @param data The collection of elements to create the data stream from.
     * @param <OUT> The generic type of the returned data stream.
     * @return The data stream representing the given collection
     */
    public <OUT> DataStreamSource<OUT> fromData(Collection<OUT> data) {
        TypeInformation<OUT> typeInfo = extractTypeInfoFromCollection(data);
        return fromData(data, typeInfo);
    }

    private static <OUT> TypeInformation<OUT> extractTypeInfoFromCollection(Collection<OUT> data) {
        Preconditions.checkNotNull(data, "Collection must not be null");
        if (data.isEmpty()) {
            throw new IllegalArgumentException("Collection must not be empty");
        }

        OUT first = data.iterator().next();
        if (first == null) {
            throw new IllegalArgumentException("Collection must not contain null elements");
        }

        TypeInformation<OUT> typeInfo;
        try {
            typeInfo = TypeExtractor.getForObject(first);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Could not create TypeInformation for type "
                            + first.getClass()
                            + "; please specify the TypeInformation manually via the version of the "
                            + "method that explicitly accepts it as an argument.",
                    e);
        }
        return typeInfo;
    }

    /**
     * Creates a new data stream that contains a sequence of numbers. This is a parallel source, if
     * you manually set the parallelism to {@code 1} (using {@link
     * org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator#setParallelism(int)})
     * the generated sequence of elements is in order.
     *
     * @param from The number to start at (inclusive)
     * @param to The number to stop at (inclusive)
     * @return A data stream, containing all number in the [from, to] interval
     * @deprecated Use {@link #fromSequence(long, long)} instead to create a new data stream that
     *     contains {@link org.apache.flink.api.connector.source.lib.NumberSequenceSource}.
     */
    @Deprecated
    public DataStreamSource<Long> generateSequence(long from, long to) {
        if (from > to) {
            throw new IllegalArgumentException(
                    "Start of sequence must not be greater than the end");
        }
        return addSource(new StatefulSequenceSource(from, to), "Sequence Source (Deprecated)");
    }

    /**
     * Creates a new data stream that contains a sequence of numbers (longs) and is useful for
     * testing and for cases that just need a stream of N events of any kind.
     *
     * <p>The generated source splits the sequence into as many parallel sub-sequences as there are
     * parallel source readers. Each sub-sequence will be produced in order. If the parallelism is
     * limited to one, the source will produce one sequence in order.
     *
     * <p>This source is always bounded. For very long sequences (for example over the entire domain
     * of long integer values), you may consider executing the application in a streaming manner
     * because of the end bound that is pretty far away.
     *
     * <p>Use {@link #fromSource(Source, WatermarkStrategy, String)} together with {@link
     * NumberSequenceSource} if you required more control over the created sources. For example, if
     * you want to set a {@link WatermarkStrategy}.
     *
     * @param from The number to start at (inclusive)
     * @param to The number to stop at (inclusive)
     */
    public DataStreamSource<Long> fromSequence(long from, long to) {
        if (from > to) {
            throw new IllegalArgumentException(
                    "Start of sequence must not be greater than the end");
        }
        return fromSource(
                new NumberSequenceSource(from, to),
                WatermarkStrategy.noWatermarks(),
                "Sequence Source");
    }

    /**
     * Creates a new data stream that contains the given elements. The elements must all be of the
     * same type, for example, all of the {@link String} or {@link Integer}.
     *
     * <p>The framework will try and determine the exact type from the elements. In case of generic
     * elements, it may be necessary to manually supply the type information via {@link
     * #fromCollection(java.util.Collection, org.apache.flink.api.common.typeinfo.TypeInformation)}.
     *
     * <p>Note that this operation will result in a non-parallel data stream source, i.e. a data
     * stream source with a degree of parallelism one.
     *
     * @param data The array of elements to create the data stream from.
     * @param <OUT> The type of the returned data stream
     * @return The data stream representing the given array of elements
     * @deprecated This method will be removed a future release, possibly as early as version 2.0.
     *     Use {@link #fromData(OUT...)} instead.
     */
    @SafeVarargs
    @Deprecated
    public final <OUT> DataStreamSource<OUT> fromElements(OUT... data) {
        if (data.length == 0) {
            throw new IllegalArgumentException(
                    "fromElements needs at least one element as argument");
        }

        TypeInformation<OUT> typeInfo;
        try {
            typeInfo = TypeExtractor.getForObject(data[0]);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Could not create TypeInformation for type "
                            + data[0].getClass().getName()
                            + "; please specify the TypeInformation manually via "
                            + "StreamExecutionEnvironment#fromElements(Collection, TypeInformation)",
                    e);
        }
        return fromCollection(Arrays.asList(data), typeInfo);
    }

    /**
     * Creates a new data stream that contains the given elements. The framework will determine the
     * type according to the based type user supplied. The elements should be the same or be the
     * subclass to the based type. The sequence of elements must not be empty. Note that this
     * operation will result in a non-parallel data stream source, i.e. a data stream source with a
     * degree of parallelism one.
     *
     * @param type The based class type in the collection.
     * @param data The array of elements to create the data stream from.
     * @param <OUT> The type of the returned data stream
     * @return The data stream representing the given array of elements
     * @deprecated This method will be removed a future release, possibly as early as version 2.0.
     *     Use {@link #fromData(OUT...)} instead.
     */
    @SafeVarargs
    @Deprecated
    public final <OUT> DataStreamSource<OUT> fromElements(Class<OUT> type, OUT... data) {
        if (data.length == 0) {
            throw new IllegalArgumentException(
                    "fromElements needs at least one element as argument");
        }

        TypeInformation<OUT> typeInfo;
        try {
            typeInfo = TypeExtractor.getForClass(type);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Could not create TypeInformation for type "
                            + type.getName()
                            + "; please specify the TypeInformation manually via "
                            + "StreamExecutionEnvironment#fromElements(Collection, TypeInformation)",
                    e);
        }
        return fromCollection(Arrays.asList(data), typeInfo);
    }

    /**
     * Creates a data stream from the given non-empty collection. The type of the data stream is
     * that of the elements in the collection.
     *
     * <p>The framework will try and determine the exact type from the collection elements. In case
     * of generic elements, it may be necessary to manually supply the type information via {@link
     * #fromCollection(java.util.Collection, org.apache.flink.api.common.typeinfo.TypeInformation)}.
     *
     * <p>Note that this operation will result in a non-parallel data stream source, i.e. a data
     * stream source with parallelism one.
     *
     * @param data The collection of elements to create the data stream from.
     * @param <OUT> The generic type of the returned data stream.
     * @return The data stream representing the given collection
     * @deprecated This method will be removed a future release, possibly as early as version 2.0.
     *     Use {@link #fromData(Collection)} instead.
     */
    public <OUT> DataStreamSource<OUT> fromCollection(Collection<OUT> data) {
        TypeInformation<OUT> typeInfo = extractTypeInfoFromCollection(data);
        return fromCollection(data, typeInfo);
    }

    /**
     * Creates a data stream from the given non-empty collection.
     *
     * <p>Note that this operation will result in a non-parallel data stream source, i.e., a data
     * stream source with parallelism one.
     *
     * @param data The collection of elements to create the data stream from
     * @param typeInfo The TypeInformation for the produced data stream
     * @param <OUT> The type of the returned data stream
     * @return The data stream representing the given collection
     * @deprecated This method will be removed a future release, possibly as early as version 2.0.
     *     Use {@link #fromData(Collection, TypeInformation)} instead.
     */
    public <OUT> DataStreamSource<OUT> fromCollection(
            Collection<OUT> data, TypeInformation<OUT> typeInfo) {
        Preconditions.checkNotNull(data, "Collection must not be null");

        // must not have null elements and mixed elements
        FromElementsFunction.checkCollection(data, typeInfo.getTypeClass());

        SourceFunction<OUT> function = new FromElementsFunction<>(data);
        return addSource(function, "Collection Source", typeInfo, Boundedness.BOUNDED)
                .setParallelism(1);
    }

    /**
     * Creates a data stream from the given iterator.
     *
     * <p>Because the iterator will remain unmodified until the actual execution happens, the type
     * of data returned by the iterator must be given explicitly in the form of the type class (this
     * is due to the fact that the Java compiler erases the generic type information).
     *
     * <p>Note that this operation will result in a non-parallel data stream source, i.e., a data
     * stream source with a parallelism of one.
     *
     * @param data The iterator of elements to create the data stream from
     * @param type The class of the data produced by the iterator. Must not be a generic class.
     * @param <OUT> The type of the returned data stream
     * @return The data stream representing the elements in the iterator
     * @see #fromCollection(java.util.Iterator,
     *     org.apache.flink.api.common.typeinfo.TypeInformation)
     * @deprecated This method will be removed a future release, possibly as early as version 2.0.
     *     Use {@link #fromData(Collection, TypeInformation)} instead. For rate-limited data
     *     generation, use {@link DataGeneratorSource} with {@link RateLimiterStrategy}. If you need
     *     to use a fixed set of elements in such scenario, combine it with {@link
     *     FromElementsGeneratorFunction}.
     */
    public <OUT> DataStreamSource<OUT> fromCollection(Iterator<OUT> data, Class<OUT> type) {
        return fromCollection(data, TypeExtractor.getForClass(type));
    }

    /**
     * Creates a data stream from the given iterator.
     *
     * <p>Because the iterator will remain unmodified until the actual execution happens, the type
     * of data returned by the iterator must be given explicitly in the form of the type
     * information. This method is useful for cases where the type is generic. In that case, the
     * type class (as given in {@link #fromCollection(java.util.Iterator, Class)} does not supply
     * all type information.
     *
     * <p>Note that this operation will result in a non-parallel data stream source, i.e., a data
     * stream source with parallelism one.
     *
     * @param data The iterator of elements to create the data stream from
     * @param typeInfo The TypeInformation for the produced data stream
     * @param <OUT> The type of the returned data stream
     * @return The data stream representing the elements in the iterator
     * @deprecated This method will be removed a future release, possibly as early as version 2.0.
     *     Use {@link #fromData(Collection, TypeInformation)} instead. For rate-limited data
     *     generation, use {@link DataGeneratorSource} with {@link RateLimiterStrategy}. If you need
     *     to use a fixed set of elements in such scenario, combine it with {@link
     *     FromElementsGeneratorFunction}.
     */
    public <OUT> DataStreamSource<OUT> fromCollection(
            Iterator<OUT> data, TypeInformation<OUT> typeInfo) {
        Preconditions.checkNotNull(data, "The iterator must not be null");

        SourceFunction<OUT> function = new FromIteratorFunction<>(data);
        return addSource(function, "Collection Source", typeInfo, Boundedness.BOUNDED);
    }

    /**
     * Creates a new data stream that contains elements in the iterator. The iterator is splittable,
     * allowing the framework to create a parallel data stream source that returns the elements in
     * the iterator.
     *
     * <p>Because the iterator will remain unmodified until the actual execution happens, the type
     * of data returned by the iterator must be given explicitly in the form of the type class (this
     * is due to the fact that the Java compiler erases the generic type information).
     *
     * @param iterator The iterator that produces the elements of the data stream
     * @param type The class of the data produced by the iterator. Must not be a generic class.
     * @param <OUT> The type of the returned data stream
     * @return A data stream representing the elements in the iterator
     */
    public <OUT> DataStreamSource<OUT> fromParallelCollection(
            SplittableIterator<OUT> iterator, Class<OUT> type) {
        return fromParallelCollection(iterator, TypeExtractor.getForClass(type));
    }

    /**
     * Creates a new data stream that contains elements in the iterator. The iterator is splittable,
     * allowing the framework to create a parallel data stream source that returns the elements in
     * the iterator.
     *
     * <p>Because the iterator will remain unmodified until the actual execution happens, the type
     * of data returned by the iterator must be given explicitly in the form of the type
     * information. This method is useful for cases where the type is generic. In that case, the
     * type class (as given in {@link
     * #fromParallelCollection(org.apache.flink.util.SplittableIterator, Class)} does not supply all
     * type information.
     *
     * @param iterator The iterator that produces the elements of the data stream
     * @param typeInfo The TypeInformation for the produced data stream.
     * @param <OUT> The type of the returned data stream
     * @return A data stream representing the elements in the iterator
     */
    public <OUT> DataStreamSource<OUT> fromParallelCollection(
            SplittableIterator<OUT> iterator, TypeInformation<OUT> typeInfo) {
        return fromParallelCollection(iterator, typeInfo, "Parallel Collection Source");
    }

    // private helper for passing different names
    private <OUT> DataStreamSource<OUT> fromParallelCollection(
            SplittableIterator<OUT> iterator, TypeInformation<OUT> typeInfo, String operatorName) {
        return addSource(
                new FromSplittableIteratorFunction<>(iterator),
                operatorName,
                typeInfo,
                Boundedness.BOUNDED);
    }

    /**
     * Reads the contents of the user-specified {@code filePath} based on the given {@link
     * FileInputFormat}.
     *
     * <p>Since all data streams need specific information about their types, this method needs to
     * determine the type of the data produced by the input format. It will attempt to determine the
     * data type by reflection, unless the input format implements the {@link
     * org.apache.flink.api.java.typeutils.ResultTypeQueryable} interface. In the latter case, this
     * method will invoke the {@link
     * org.apache.flink.api.java.typeutils.ResultTypeQueryable#getProducedType()} method to
     * determine data type produced by the input format.
     *
     * <p><b>NOTES ON CHECKPOINTING: </b> The source monitors the path, creates the {@link
     * org.apache.flink.core.fs.FileInputSplit FileInputSplits} to be processed, forwards them to
     * the downstream readers to read the actual data, and exits, without waiting for the readers to
     * finish reading. This implies that no more checkpoint barriers are going to be forwarded after
     * the source exits, thus having no checkpoints after that point.
     *
     * @param filePath The path of the file, as a URI (e.g., "file:///some/local/file" or
     *     "hdfs://host:port/file/path")
     * @param inputFormat The input format used to create the data stream
     * @param <OUT> The type of the returned data stream
     * @return The data stream that represents the data read from the given file
     * @deprecated Use {@code
     *     FileSource#forRecordStreamFormat()/forBulkFileFormat()/forRecordFileFormat() instead}. An
     *     example of reading a file using a simple {@code TextLineInputFormat}:
     *     <pre>{@code
     * FileSource<String> source =
     *        FileSource.forRecordStreamFormat(
     *           new TextLineInputFormat(), new Path("/foo/bar"))
     *        .build();
     * }</pre>
     */
    @Deprecated
    public <OUT> DataStreamSource<OUT> readFile(FileInputFormat<OUT> inputFormat, String filePath) {
        return readFile(inputFormat, filePath, FileProcessingMode.PROCESS_ONCE, -1);
    }

    /**
     * Reads the contents of the user-specified {@code filePath} based on the given {@link
     * FileInputFormat}. Depending on the provided {@link FileProcessingMode}.
     *
     * <p>See {@link #readFile(FileInputFormat, String, FileProcessingMode, long)}
     *
     * @param inputFormat The input format used to create the data stream
     * @param filePath The path of the file, as a URI (e.g., "file:///some/local/file" or
     *     "hdfs://host:port/file/path")
     * @param watchType The mode in which the source should operate, i.e. monitor path and react to
     *     new data, or process once and exit
     * @param interval In the case of periodic path monitoring, this specifies the interval (in
     *     millis) between consecutive path scans
     * @param filter The files to be excluded from the processing
     * @param <OUT> The type of the returned data stream
     * @return The data stream that represents the data read from the given file
     * @deprecated Use {@link FileInputFormat#setFilesFilter(FilePathFilter)} to set a filter and
     *     {@link StreamExecutionEnvironment#readFile(FileInputFormat, String, FileProcessingMode,
     *     long)}
     */
    @PublicEvolving
    @Deprecated
    public <OUT> DataStreamSource<OUT> readFile(
            FileInputFormat<OUT> inputFormat,
            String filePath,
            FileProcessingMode watchType,
            long interval,
            FilePathFilter filter) {
        inputFormat.setFilesFilter(filter);

        TypeInformation<OUT> typeInformation;
        try {
            typeInformation = TypeExtractor.getInputFormatTypes(inputFormat);
        } catch (Exception e) {
            throw new InvalidProgramException(
                    "The type returned by the input format could not be "
                            + "automatically determined. Please specify the TypeInformation of the produced type "
                            + "explicitly by using the 'createInput(InputFormat, TypeInformation)' method instead.");
        }
        return readFile(inputFormat, filePath, watchType, interval, typeInformation);
    }

    /**
     * Reads the contents of the user-specified {@code filePath} based on the given {@link
     * FileInputFormat}. Depending on the provided {@link FileProcessingMode}, the source may
     * periodically monitor (every {@code interval} ms) the path for new data ({@link
     * FileProcessingMode#PROCESS_CONTINUOUSLY}), or process once the data currently in the path and
     * exit ({@link FileProcessingMode#PROCESS_ONCE}). In addition, if the path contains files not
     * to be processed, the user can specify a custom {@link FilePathFilter}. As a default
     * implementation you can use {@link FilePathFilter#createDefaultFilter()}.
     *
     * <p>Since all data streams need specific information about their types, this method needs to
     * determine the type of the data produced by the input format. It will attempt to determine the
     * data type by reflection, unless the input format implements the {@link
     * org.apache.flink.api.java.typeutils.ResultTypeQueryable} interface. In the latter case, this
     * method will invoke the {@link
     * org.apache.flink.api.java.typeutils.ResultTypeQueryable#getProducedType()} method to
     * determine data type produced by the input format.
     *
     * <p><b>NOTES ON CHECKPOINTING: </b> If the {@code watchType} is set to {@link
     * FileProcessingMode#PROCESS_ONCE}, the source monitors the path <b>once</b>, creates the
     * {@link org.apache.flink.core.fs.FileInputSplit FileInputSplits} to be processed, forwards
     * them to the downstream readers to read the actual data, and exits, without waiting for the
     * readers to finish reading. This implies that no more checkpoint barriers are going to be
     * forwarded after the source exits, thus having no checkpoints after that point.
     *
     * @param inputFormat The input format used to create the data stream
     * @param filePath The path of the file, as a URI (e.g., "file:///some/local/file" or
     *     "hdfs://host:port/file/path")
     * @param watchType The mode in which the source should operate, i.e. monitor path and react to
     *     new data, or process once and exit
     * @param interval In the case of periodic path monitoring, this specifies the interval (in
     *     millis) between consecutive path scans
     * @param <OUT> The type of the returned data stream
     * @return The data stream that represents the data read from the given file
     * @deprecated Use {@code
     *     FileSource#forRecordStreamFormat()/forBulkFileFormat()/forRecordFileFormat() instead}. An
     *     example of reading a file using a simple {@code TextLineInputFormat}:
     *     <pre>{@code
     * FileSource<String> source =
     *        FileSource.forRecordStreamFormat(
     *           new TextLineInputFormat(), new Path("/foo/bar"))
     *        .monitorContinuously(Duration.of(10, SECONDS))
     *        .build();
     * }</pre>
     */
    @Deprecated
    @PublicEvolving
    public <OUT> DataStreamSource<OUT> readFile(
            FileInputFormat<OUT> inputFormat,
            String filePath,
            FileProcessingMode watchType,
            long interval) {

        TypeInformation<OUT> typeInformation;
        try {
            typeInformation = TypeExtractor.getInputFormatTypes(inputFormat);
        } catch (Exception e) {
            throw new InvalidProgramException(
                    "The type returned by the input format could not be "
                            + "automatically determined. Please specify the TypeInformation of the produced type "
                            + "explicitly by using the 'createInput(InputFormat, TypeInformation)' method instead.");
        }
        return readFile(inputFormat, filePath, watchType, interval, typeInformation);
    }

    /**
     * Creates a data stream that contains the contents of file created while system watches the
     * given path. The file will be read with the system's default character set.
     *
     * @param filePath The path of the file, as a URI (e.g., "file:///some/local/file" or
     *     "hdfs://host:port/file/path/")
     * @param intervalMillis The interval of file watching in milliseconds
     * @param watchType The watch type of file stream. When watchType is {@link
     *     FileMonitoringFunction.WatchType#ONLY_NEW_FILES}, the system processes only new files.
     *     {@link FileMonitoringFunction.WatchType#REPROCESS_WITH_APPENDED} means that the system
     *     re-processes all contents of appended file. {@link
     *     FileMonitoringFunction.WatchType#PROCESS_ONLY_APPENDED} means that the system processes
     *     only appended contents of files.
     * @return The DataStream containing the given directory.
     * @deprecated Use {@link #readFile(FileInputFormat, String, FileProcessingMode, long)} instead.
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public DataStream<String> readFileStream(
            String filePath, long intervalMillis, FileMonitoringFunction.WatchType watchType) {
        DataStream<Tuple3<String, Long, Long>> source =
                addSource(
                        new FileMonitoringFunction(filePath, intervalMillis, watchType),
                        "Read File Stream source");

        return source.flatMap(new FileReadFunction());
    }

    /**
     * Reads the contents of the user-specified {@code filePath} based on the given {@link
     * FileInputFormat}. Depending on the provided {@link FileProcessingMode}, the source may
     * periodically monitor (every {@code interval} ms) the path for new data ({@link
     * FileProcessingMode#PROCESS_CONTINUOUSLY}), or process once the data currently in the path and
     * exit ({@link FileProcessingMode#PROCESS_ONCE}). In addition, if the path contains files not
     * to be processed, the user can specify a custom {@link FilePathFilter}. As a default
     * implementation you can use {@link FilePathFilter#createDefaultFilter()}.
     *
     * <p><b>NOTES ON CHECKPOINTING: </b> If the {@code watchType} is set to {@link
     * FileProcessingMode#PROCESS_ONCE}, the source monitors the path <b>once</b>, creates the
     * {@link org.apache.flink.core.fs.FileInputSplit FileInputSplits} to be processed, forwards
     * them to the downstream readers to read the actual data, and exits, without waiting for the
     * readers to finish reading. This implies that no more checkpoint barriers are going to be
     * forwarded after the source exits, thus having no checkpoints after that point.
     *
     * @param inputFormat The input format used to create the data stream
     * @param filePath The path of the file, as a URI (e.g., "file:///some/local/file" or
     *     "hdfs://host:port/file/path")
     * @param watchType The mode in which the source should operate, i.e. monitor path and react to
     *     new data, or process once and exit
     * @param typeInformation Information on the type of the elements in the output stream
     * @param interval In the case of periodic path monitoring, this specifies the interval (in
     *     millis) between consecutive path scans
     * @param <OUT> The type of the returned data stream
     * @return The data stream that represents the data read from the given file
     * @deprecated Use {@code
     *     FileSource#forRecordStreamFormat()/forBulkFileFormat()/forRecordFileFormat() instead}. An
     *     example of reading a file using a simple {@code TextLineInputFormat}:
     *     <pre>{@code
     * FileSource<String> source =
     *        FileSource.forRecordStreamFormat(
     *           new TextLineInputFormat(), new Path("/foo/bar"))
     *        .monitorContinuously(Duration.of(10, SECONDS))
     *        .build();
     * }</pre>
     */
    @Deprecated
    @PublicEvolving
    public <OUT> DataStreamSource<OUT> readFile(
            FileInputFormat<OUT> inputFormat,
            String filePath,
            FileProcessingMode watchType,
            long interval,
            TypeInformation<OUT> typeInformation) {

        Preconditions.checkNotNull(inputFormat, "InputFormat must not be null.");
        Preconditions.checkArgument(
                !StringUtils.isNullOrWhitespaceOnly(filePath),
                "The file path must not be null or blank.");

        inputFormat.setFilePath(filePath);
        return createFileInput(
                inputFormat, typeInformation, "Custom File Source", watchType, interval);
    }

    /**
     * Creates a new data stream that contains the strings received infinitely from a socket.
     * Received strings are decoded by the system's default character set. On the termination of the
     * socket server connection retries can be initiated.
     *
     * <p>Let us note that the socket itself does not report on abort and as a consequence retries
     * are only initiated when the socket was gracefully terminated.
     *
     * @param hostname The host name which a server socket binds
     * @param port The port number which a server socket binds. A port number of 0 means that the
     *     port number is automatically allocated.
     * @param delimiter A character which splits received strings into records
     * @param maxRetry The maximal retry interval in seconds while the program waits for a socket
     *     that is temporarily down. Reconnection is initiated every second. A number of 0 means
     *     that the reader is immediately terminated, while a negative value ensures retrying
     *     forever.
     * @return A data stream containing the strings received from the socket
     * @deprecated Use {@link #socketTextStream(String, int, String, long)} instead.
     */
    @Deprecated
    public DataStreamSource<String> socketTextStream(
            String hostname, int port, char delimiter, long maxRetry) {
        return socketTextStream(hostname, port, String.valueOf(delimiter), maxRetry);
    }

    /**
     * Creates a new data stream that contains the strings received infinitely from a socket.
     * Received strings are decoded by the system's default character set. On the termination of the
     * socket server connection retries can be initiated.
     *
     * <p>Let us note that the socket itself does not report on abort and as a consequence retries
     * are only initiated when the socket was gracefully terminated.
     *
     * @param hostname The host name which a server socket binds
     * @param port The port number which a server socket binds. A port number of 0 means that the
     *     port number is automatically allocated.
     * @param delimiter A string which splits received strings into records
     * @param maxRetry The maximal retry interval in seconds while the program waits for a socket
     *     that is temporarily down. Reconnection is initiated every second. A number of 0 means
     *     that the reader is immediately terminated, while a negative value ensures retrying
     *     forever.
     * @return A data stream containing the strings received from the socket
     */
    @PublicEvolving
    public DataStreamSource<String> socketTextStream(
            String hostname, int port, String delimiter, long maxRetry) {
        return addSource(
                new SocketTextStreamFunction(hostname, port, delimiter, maxRetry), "Socket Stream");
    }

    /**
     * Creates a new data stream that contains the strings received infinitely from a socket.
     * Received strings are decoded by the system's default character set. The reader is terminated
     * immediately when the socket is down.
     *
     * @param hostname The host name which a server socket binds
     * @param port The port number which a server socket binds. A port number of 0 means that the
     *     port number is automatically allocated.
     * @param delimiter A character which splits received strings into records
     * @return A data stream containing the strings received from the socket
     * @deprecated Use {@link #socketTextStream(String, int, String)} instead.
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public DataStreamSource<String> socketTextStream(String hostname, int port, char delimiter) {
        return socketTextStream(hostname, port, delimiter, 0);
    }

    /**
     * Creates a new data stream that contains the strings received infinitely from a socket.
     * Received strings are decoded by the system's default character set. The reader is terminated
     * immediately when the socket is down.
     *
     * @param hostname The host name which a server socket binds
     * @param port The port number which a server socket binds. A port number of 0 means that the
     *     port number is automatically allocated.
     * @param delimiter A string which splits received strings into records
     * @return A data stream containing the strings received from the socket
     */
    @PublicEvolving
    public DataStreamSource<String> socketTextStream(String hostname, int port, String delimiter) {
        return socketTextStream(hostname, port, delimiter, 0);
    }

    /**
     * Creates a new data stream that contains the strings received infinitely from a socket.
     * Received strings are decoded by the system's default character set, using"\n" as delimiter.
     * The reader is terminated immediately when the socket is down.
     *
     * @param hostname The host name which a server socket binds
     * @param port The port number which a server socket binds. A port number of 0 means that the
     *     port number is automatically allocated.
     * @return A data stream containing the strings received from the socket
     */
    @PublicEvolving
    public DataStreamSource<String> socketTextStream(String hostname, int port) {
        return socketTextStream(hostname, port, "\n");
    }

    /**
     * Generic method to create an input data stream with {@link
     * org.apache.flink.api.common.io.InputFormat}.
     *
     * <p>Since all data streams need specific information about their types, this method needs to
     * determine the type of the data produced by the input format. It will attempt to determine the
     * data type by reflection, unless the input format implements the {@link
     * org.apache.flink.api.java.typeutils.ResultTypeQueryable} interface. In the latter case, this
     * method will invoke the {@link
     * org.apache.flink.api.java.typeutils.ResultTypeQueryable#getProducedType()} method to
     * determine data type produced by the input format.
     *
     * <p><b>NOTES ON CHECKPOINTING: </b> In the case of a {@link FileInputFormat}, the source
     * (which executes the {@link ContinuousFileMonitoringFunction}) monitors the path, creates the
     * {@link org.apache.flink.core.fs.FileInputSplit FileInputSplits} to be processed, forwards
     * them to the downstream readers to read the actual data, and exits, without waiting for the
     * readers to finish reading. This implies that no more checkpoint barriers are going to be
     * forwarded after the source exits, thus having no checkpoints.
     *
     * @param inputFormat The input format used to create the data stream
     * @param <OUT> The type of the returned data stream
     * @return The data stream that represents the data created by the input format
     */
    @PublicEvolving
    public <OUT> DataStreamSource<OUT> createInput(InputFormat<OUT, ?> inputFormat) {
        return createInput(inputFormat, TypeExtractor.getInputFormatTypes(inputFormat));
    }

    /**
     * Generic method to create an input data stream with {@link
     * org.apache.flink.api.common.io.InputFormat}.
     *
     * <p>The data stream is typed to the given TypeInformation. This method is intended for input
     * formats where the return type cannot be determined by reflection analysis, and that do not
     * implement the {@link org.apache.flink.api.java.typeutils.ResultTypeQueryable} interface.
     *
     * <p><b>NOTES ON CHECKPOINTING: </b> In the case of a {@link FileInputFormat}, the source
     * (which executes the {@link ContinuousFileMonitoringFunction}) monitors the path, creates the
     * {@link org.apache.flink.core.fs.FileInputSplit FileInputSplits} to be processed, forwards
     * them to the downstream readers to read the actual data, and exits, without waiting for the
     * readers to finish reading. This implies that no more checkpoint barriers are going to be
     * forwarded after the source exits, thus having no checkpoints.
     *
     * @param inputFormat The input format used to create the data stream
     * @param typeInfo The information about the type of the output type
     * @param <OUT> The type of the returned data stream
     * @return The data stream that represents the data created by the input format
     */
    @PublicEvolving
    public <OUT> DataStreamSource<OUT> createInput(
            InputFormat<OUT, ?> inputFormat, TypeInformation<OUT> typeInfo) {
        DataStreamSource<OUT> source;

        if (inputFormat instanceof FileInputFormat) {
            @SuppressWarnings("unchecked")
            FileInputFormat<OUT> format = (FileInputFormat<OUT>) inputFormat;

            source =
                    createFileInput(
                            format,
                            typeInfo,
                            "Custom File source",
                            FileProcessingMode.PROCESS_ONCE,
                            -1);
        } else {
            source = createInput(inputFormat, typeInfo, "Custom Source");
        }
        return source;
    }

    private <OUT> DataStreamSource<OUT> createInput(
            InputFormat<OUT, ?> inputFormat, TypeInformation<OUT> typeInfo, String sourceName) {

        InputFormatSourceFunction<OUT> function =
                new InputFormatSourceFunction<>(inputFormat, typeInfo);
        return addSource(function, sourceName, typeInfo);
    }

    private <OUT> DataStreamSource<OUT> createFileInput(
            FileInputFormat<OUT> inputFormat,
            TypeInformation<OUT> typeInfo,
            String sourceName,
            FileProcessingMode monitoringMode,
            long interval) {

        Preconditions.checkNotNull(inputFormat, "Unspecified file input format.");
        Preconditions.checkNotNull(typeInfo, "Unspecified output type information.");
        Preconditions.checkNotNull(sourceName, "Unspecified name for the source.");
        Preconditions.checkNotNull(monitoringMode, "Unspecified monitoring mode.");

        Preconditions.checkArgument(
                monitoringMode.equals(FileProcessingMode.PROCESS_ONCE)
                        || interval >= ContinuousFileMonitoringFunction.MIN_MONITORING_INTERVAL,
                "The path monitoring interval cannot be less than "
                        + ContinuousFileMonitoringFunction.MIN_MONITORING_INTERVAL
                        + " ms.");

        ContinuousFileMonitoringFunction<OUT> monitoringFunction =
                new ContinuousFileMonitoringFunction<>(
                        inputFormat, monitoringMode, getParallelism(), interval);

        ContinuousFileReaderOperatorFactory<OUT, TimestampedFileInputSplit> factory =
                new ContinuousFileReaderOperatorFactory<>(inputFormat);

        final Boundedness boundedness =
                monitoringMode == FileProcessingMode.PROCESS_ONCE
                        ? Boundedness.BOUNDED
                        : Boundedness.CONTINUOUS_UNBOUNDED;

        SingleOutputStreamOperator<OUT> source =
                addSource(monitoringFunction, sourceName, null, boundedness)
                        // Set the parallelism and maximum parallelism of
                        // ContinuousFileMonitoringFunction to 1 in
                        // case reactive mode changes it. See FLINK-28274 for more information.
                        .forceNonParallel()
                        .transform("Split Reader: " + sourceName, typeInfo, factory);

        return new DataStreamSource<>(source);
    }

    /**
     * Adds a Data Source to the streaming topology.
     *
     * <p>By default sources have a parallelism of 1. To enable parallel execution, the user defined
     * source should implement {@link ParallelSourceFunction} or extend {@link
     * RichParallelSourceFunction}. In these cases the resulting source will have the parallelism of
     * the environment. To change this afterwards call {@link
     * org.apache.flink.streaming.api.datastream.DataStreamSource#setParallelism(int)}
     *
     * @param function the user defined function
     * @param <OUT> type of the returned stream
     * @return the data stream constructed
     * @deprecated This method relies on the {@link SourceFunction} API, which is due to be removed.
     *     Use the {@link #fromSource(Source, WatermarkStrategy, String)} method based on the new
     *     {@link org.apache.flink.api.connector.source.Source} API instead.
     */
    @Deprecated
    public <OUT> DataStreamSource<OUT> addSource(SourceFunction<OUT> function) {
        return addSource(function, "Custom Source");
    }

    /**
     * Adds a data source with a custom type information thus opening a {@link DataStream}. Only in
     * very special cases does the user need to support type information. Otherwise use {@link
     * #addSource(SourceFunction)}
     *
     * @param function the user defined function
     * @param sourceName Name of the data source
     * @param <OUT> type of the returned stream
     * @return the data stream constructed
     * @deprecated This method relies on the {@link SourceFunction} API, which is due to be removed.
     *     Use the {@link #fromSource(Source, WatermarkStrategy, String)} method based on the new
     *     {@link org.apache.flink.api.connector.source.Source} API instead.
     */
    @Internal
    public <OUT> DataStreamSource<OUT> addSource(SourceFunction<OUT> function, String sourceName) {
        return addSource(function, sourceName, null);
    }

    /**
     * Ads a data source with a custom type information thus opening a {@link DataStream}. Only in
     * very special cases does the user need to support type information. Otherwise use {@link
     * #addSource(SourceFunction)}
     *
     * @param function the user defined function
     * @param <OUT> type of the returned stream
     * @param typeInfo the user defined type information for the stream
     * @return the data stream constructed
     * @deprecated This method relies on the {@link SourceFunction} API, which is due to be removed.
     *     Use the {@link #fromSource(Source, WatermarkStrategy, String, TypeInformation)} method
     *     based on the new {@link org.apache.flink.api.connector.source.Source} API instead.
     */
    @Internal
    public <OUT> DataStreamSource<OUT> addSource(
            SourceFunction<OUT> function, TypeInformation<OUT> typeInfo) {
        return addSource(function, "Custom Source", typeInfo);
    }

    /**
     * Ads a data source with a custom type information thus opening a {@link DataStream}. Only in
     * very special cases does the user need to support type information. Otherwise use {@link
     * #addSource(SourceFunction)}
     *
     * @param function the user defined function
     * @param sourceName Name of the data source
     * @param <OUT> type of the returned stream
     * @param typeInfo the user defined type information for the stream
     * @return the data stream constructed
     * @deprecated This method relies on the {@link SourceFunction} API, which is due to be removed.
     *     Use the {@link #fromSource(Source, WatermarkStrategy, String, TypeInformation)} method
     *     based on the new {@link org.apache.flink.api.connector.source.Source} API instead.
     */
    @Internal
    public <OUT> DataStreamSource<OUT> addSource(
            SourceFunction<OUT> function, String sourceName, TypeInformation<OUT> typeInfo) {
        return addSource(function, sourceName, typeInfo, Boundedness.CONTINUOUS_UNBOUNDED);
    }

    private <OUT> DataStreamSource<OUT> addSource(
            final SourceFunction<OUT> function,
            final String sourceName,
            @Nullable final TypeInformation<OUT> typeInfo,
            final Boundedness boundedness) {
        checkNotNull(function);
        checkNotNull(sourceName);
        checkNotNull(boundedness);

        TypeInformation<OUT> resolvedTypeInfo =
                getTypeInfo(function, sourceName, SourceFunction.class, typeInfo);

        boolean isParallel = function instanceof ParallelSourceFunction;

        clean(function);

        final StreamSource<OUT, ?> sourceOperator = new StreamSource<>(function);
        return new DataStreamSource<>(
                this, resolvedTypeInfo, sourceOperator, isParallel, sourceName, boundedness);
    }

    /**
     * Adds a data {@link Source} to the environment to get a {@link DataStream}.
     *
     * <p>The result will be either a bounded data stream (that can be processed in a batch way) or
     * an unbounded data stream (that must be processed in a streaming way), based on the
     * boundedness property of the source, as defined by {@link Source#getBoundedness()}.
     *
     * <p>The result type (that is used to create serializers for the produced data events) will be
     * automatically extracted. This is useful for sources that describe the produced types already
     * in their configuration, to avoid having to declare the type multiple times. For example the
     * file sources and Kafka sources already define the produced byte their
     * parsers/serializers/formats, and can forward that information.
     *
     * @param source the user defined source
     * @param sourceName Name of the data source
     * @param <OUT> type of the returned stream
     * @return the data stream constructed
     */
    @PublicEvolving
    public <OUT> DataStreamSource<OUT> fromSource(
            Source<OUT, ?, ?> source,
            WatermarkStrategy<OUT> timestampsAndWatermarks,
            String sourceName) {
        return fromSource(source, timestampsAndWatermarks, sourceName, null);
    }

    /**
     * Adds a data {@link Source} to the environment to get a {@link DataStream}.
     *
     * <p>The result will be either a bounded data stream (that can be processed in a batch way) or
     * an unbounded data stream (that must be processed in a streaming way), based on the
     * boundedness property of the source, as defined by {@link Source#getBoundedness()}.
     *
     * <p>This method takes an explicit type information for the produced data stream, so that
     * callers can define directly what type/serializer will be used for the produced stream. For
     * sources that describe their produced type, the method {@link #fromSource(Source,
     * WatermarkStrategy, String)} can be used to avoid specifying the produced type redundantly.
     *
     * @param source the user defined source
     * @param sourceName Name of the data source
     * @param <OUT> type of the returned stream
     * @param typeInfo the user defined type information for the stream
     * @return the data stream constructed
     */
    @Experimental
    public <OUT> DataStreamSource<OUT> fromSource(
            Source<OUT, ?, ?> source,
            WatermarkStrategy<OUT> timestampsAndWatermarks,
            String sourceName,
            TypeInformation<OUT> typeInfo) {

        final TypeInformation<OUT> resolvedTypeInfo =
                getTypeInfo(source, sourceName, Source.class, typeInfo);

        return new DataStreamSource<>(
                this,
                checkNotNull(source, "source"),
                checkNotNull(timestampsAndWatermarks, "timestampsAndWatermarks"),
                checkNotNull(resolvedTypeInfo),
                checkNotNull(sourceName));
    }

    /**
     * Triggers the program execution. The environment will execute all parts of the program that
     * have resulted in a "sink" operation. Sink operations are for example printing results or
     * forwarding them to a message queue.
     *
     * <p>The program execution will be logged and displayed with a generated default name.
     *
     * @return The result of the job execution, containing elapsed time and accumulators.
     * @throws Exception which occurs during job execution.
     */
    public JobExecutionResult execute() throws Exception {
        return execute((String) null);
    }

    /**
     * Triggers the program execution. The environment will execute all parts of the program that
     * have resulted in a "sink" operation. Sink operations are for example printing results or
     * forwarding them to a message queue.
     *
     * <p>The program execution will be logged and displayed with the provided name
     *
     * @param jobName Desired name of the job
     * @return The result of the job execution, containing elapsed time and accumulators.
     * @throws Exception which occurs during job execution.
     */
    public JobExecutionResult execute(String jobName) throws Exception {
        final List<Transformation<?>> originalTransformations = new ArrayList<>(transformations);
        StreamGraph streamGraph = getStreamGraph();
        if (jobName != null) {
            streamGraph.setJobName(jobName);
        }

        try {
            return execute(streamGraph);
        } catch (Throwable t) {
            Optional<ClusterDatasetCorruptedException> clusterDatasetCorruptedException =
                    ExceptionUtils.findThrowable(t, ClusterDatasetCorruptedException.class);
            if (!clusterDatasetCorruptedException.isPresent()) {
                throw t;
            }

            // Retry without cache if it is caused by corrupted cluster dataset.
            invalidateCacheTransformations(originalTransformations);
            streamGraph = getStreamGraph(originalTransformations);
            return execute(streamGraph);
        }
    }

    /**
     * Triggers the program execution. The environment will execute all parts of the program that
     * have resulted in a "sink" operation. Sink operations are for example printing results or
     * forwarding them to a message queue.
     *
     * @param streamGraph the stream graph representing the transformations
     * @return The result of the job execution, containing elapsed time and accumulators.
     * @throws Exception which occurs during job execution.
     */
    @Internal
    public JobExecutionResult execute(StreamGraph streamGraph) throws Exception {
        final JobClient jobClient = executeAsync(streamGraph);

        try {
            final JobExecutionResult jobExecutionResult;

            if (configuration.get(DeploymentOptions.ATTACHED)) {
                jobExecutionResult = jobClient.getJobExecutionResult().get();
            } else {
                jobExecutionResult = new DetachedJobExecutionResult(jobClient.getJobID());
            }

            jobListeners.forEach(
                    jobListener -> jobListener.onJobExecuted(jobExecutionResult, null));

            return jobExecutionResult;
        } catch (Throwable t) {
            // get() on the JobExecutionResult Future will throw an ExecutionException. This
            // behaviour was largely not there in Flink versions before the PipelineExecutor
            // refactoring so we should strip that exception.
            Throwable strippedException = ExceptionUtils.stripExecutionException(t);

            jobListeners.forEach(
                    jobListener -> {
                        jobListener.onJobExecuted(null, strippedException);
                    });
            ExceptionUtils.rethrowException(strippedException);

            // never reached, only make javac happy
            return null;
        }
    }

    private void invalidateCacheTransformations(List<Transformation<?>> transformations)
            throws Exception {
        for (Transformation<?> transformation : transformations) {
            if (transformation == null) {
                continue;
            }
            if (transformation instanceof CacheTransformation) {
                invalidateClusterDataset(((CacheTransformation<?>) transformation).getDatasetId());
            }
            invalidateCacheTransformations(transformation.getInputs());
        }
    }

    /**
     * Register a {@link JobListener} in this environment. The {@link JobListener} will be notified
     * on specific job status changed.
     */
    @PublicEvolving
    public void registerJobListener(JobListener jobListener) {
        checkNotNull(jobListener, "JobListener cannot be null");
        List<String> listeners =
                this.configuration
                        .getOptional(DeploymentOptions.JOB_LISTENERS)
                        .orElse(new ArrayList<>());
        listeners.add(jobListener.getClass().getName());
        this.configuration.set(DeploymentOptions.JOB_LISTENERS, listeners);
        jobListeners.add(jobListener);
    }

    /** Clear all registered {@link JobListener}s. */
    @PublicEvolving
    public void clearJobListeners() {
        this.jobListeners.clear();
        this.configuration.removeConfig(DeploymentOptions.JOB_LISTENERS);
    }

    /**
     * Triggers the program asynchronously. The environment will execute all parts of the program
     * that have resulted in a "sink" operation. Sink operations are for example printing results or
     * forwarding them to a message queue.
     *
     * <p>The program execution will be logged and displayed with a generated default name.
     *
     * @return A {@link JobClient} that can be used to communicate with the submitted job, completed
     *     on submission succeeded.
     * @throws Exception which occurs during job execution.
     */
    @PublicEvolving
    public final JobClient executeAsync() throws Exception {
        return executeAsync(getStreamGraph());
    }

    /**
     * Triggers the program execution asynchronously. The environment will execute all parts of the
     * program that have resulted in a "sink" operation. Sink operations are for example printing
     * results or forwarding them to a message queue.
     *
     * <p>The program execution will be logged and displayed with the provided name
     *
     * @param jobName desired name of the job
     * @return A {@link JobClient} that can be used to communicate with the submitted job, completed
     *     on submission succeeded.
     * @throws Exception which occurs during job execution.
     */
    @PublicEvolving
    public JobClient executeAsync(String jobName) throws Exception {
        final StreamGraph streamGraph = getStreamGraph();
        if (jobName != null) {
            streamGraph.setJobName(jobName);
        }
        return executeAsync(streamGraph);
    }

    /**
     * Triggers the program execution asynchronously. The environment will execute all parts of the
     * program that have resulted in a "sink" operation. Sink operations are for example printing
     * results or forwarding them to a message queue.
     *
     * @param streamGraph the stream graph representing the transformations
     * @return A {@link JobClient} that can be used to communicate with the submitted job, completed
     *     on submission succeeded.
     * @throws Exception which occurs during job execution.
     */
    @Internal
    public JobClient executeAsync(StreamGraph streamGraph) throws Exception {
        checkNotNull(streamGraph, "StreamGraph cannot be null.");
        final PipelineExecutor executor = getPipelineExecutor();

        CompletableFuture<JobClient> jobClientFuture =
                executor.execute(streamGraph, configuration, userClassloader);

        try {
            JobClient jobClient = jobClientFuture.get();
            jobListeners.forEach(jobListener -> jobListener.onJobSubmitted(jobClient, null));
            collectIterators.forEach(iterator -> iterator.setJobClient(jobClient));
            collectIterators.clear();
            return jobClient;
        } catch (ExecutionException executionException) {
            final Throwable strippedException =
                    ExceptionUtils.stripExecutionException(executionException);
            jobListeners.forEach(
                    jobListener -> jobListener.onJobSubmitted(null, strippedException));

            throw new FlinkException(
                    String.format("Failed to execute job '%s'.", streamGraph.getJobName()),
                    strippedException);
        }
    }

    /**
     * Getter of the {@link StreamGraph} of the streaming job. This call clears previously
     * registered {@link Transformation transformations}.
     *
     * @return The stream graph representing the transformations
     */
    @Internal
    public StreamGraph getStreamGraph() {
        return getStreamGraph(true);
    }

    /**
     * Getter of the {@link StreamGraph} of the streaming job with the option to clear previously
     * registered {@link Transformation transformations}. Clearing the transformations allows, for
     * example, to not re-execute the same operations when calling {@link #execute()} multiple
     * times.
     *
     * @param clearTransformations Whether or not to clear previously registered transformations
     * @return The stream graph representing the transformations
     */
    @Internal
    public StreamGraph getStreamGraph(boolean clearTransformations) {
        final StreamGraph streamGraph = getStreamGraph(transformations);
        if (clearTransformations) {
            transformations.clear();
        }
        return streamGraph;
    }

    private StreamGraph getStreamGraph(List<Transformation<?>> transformations) {
        synchronizeClusterDatasetStatus();
        return getStreamGraphGenerator(transformations).generate();
    }

    private void synchronizeClusterDatasetStatus() {
        if (cachedTransformations.isEmpty()) {
            return;
        }
        Set<AbstractID> completedClusterDatasets =
                listCompletedClusterDatasets().stream()
                        .map(AbstractID::new)
                        .collect(Collectors.toSet());
        cachedTransformations.forEach(
                (id, transformation) -> {
                    transformation.setCached(completedClusterDatasets.contains(id));
                });
    }

    /**
     * Generates a {@link StreamGraph} that consists of the given {@link Transformation
     * transformations} and is configured with the configuration of this environment.
     *
     * <p>This method does not access or clear the previously registered transformations.
     *
     * @param transformations list of transformations that the graph should contain
     * @return The stream graph representing the transformations
     */
    @Internal
    public StreamGraph generateStreamGraph(List<Transformation<?>> transformations) {
        return getStreamGraphGenerator(transformations).generate();
    }

    private StreamGraphGenerator getStreamGraphGenerator(List<Transformation<?>> transformations) {
        if (transformations.size() <= 0) {
            throw new IllegalStateException(
                    "No operators defined in streaming topology. Cannot execute.");
        }

        // Synchronize the cached file to config option PipelineOptions.CACHED_FILES because the
        // field cachedFile haven't been migrated to configuration.
        if (!getCachedFiles().isEmpty()) {
            configuration.set(
                    PipelineOptions.CACHED_FILES,
                    DistributedCache.parseStringFromCachedFiles(getCachedFiles()));
        }

        // We copy the transformation so that newly added transformations cannot intervene with the
        // stream graph generation.
        return new StreamGraphGenerator(
                        new ArrayList<>(transformations), config, checkpointCfg, configuration)
                .setSlotSharingGroupResource(slotSharingGroupResources);
    }

    /**
     * Creates the plan with which the system will execute the program, and returns it as a String
     * using a JSON representation of the execution data flow graph. Note that this needs to be
     * called, before the plan is executed.
     *
     * @return The execution plan of the program, as a JSON String.
     */
    public String getExecutionPlan() {
        return getStreamGraph(false).getStreamingPlanAsJSON();
    }

    /**
     * Returns a "closure-cleaned" version of the given function. Cleans only if closure cleaning is
     * not disabled in the {@link org.apache.flink.api.common.ExecutionConfig}
     */
    @Internal
    public <F> F clean(F f) {
        if (getConfig().isClosureCleanerEnabled()) {
            ClosureCleaner.clean(f, getConfig().getClosureCleanerLevel(), true);
        }
        ClosureCleaner.ensureSerializable(f);
        return f;
    }

    /**
     * Adds an operator to the list of operators that should be executed when calling {@link
     * #execute}.
     *
     * <p>When calling {@link #execute()} only the operators that where previously added to the list
     * are executed.
     *
     * <p>This is not meant to be used by users. The API methods that create operators must call
     * this method.
     */
    @Internal
    public void addOperator(Transformation<?> transformation) {
        Preconditions.checkNotNull(transformation, "transformation must not be null.");
        this.transformations.add(transformation);
    }

    /**
     * Gives read-only access to the underlying configuration of this environment.
     *
     * <p>Note that the returned configuration might not be complete. It only contains options that
     * have initialized the environment via {@link #StreamExecutionEnvironment(Configuration)} or
     * options that are not represented in dedicated configuration classes such as {@link
     * ExecutionConfig} or {@link CheckpointConfig}.
     *
     * <p>Use {@link #configure(ReadableConfig, ClassLoader)} to set options that are specific to
     * this environment.
     */
    @Internal
    public ReadableConfig getConfiguration() {
        // Note to implementers:
        // In theory, you can cast the return value of this method to Configuration and perform
        // mutations. In practice, this could cause side effects. A better approach is to implement
        // the ReadableConfig interface and create a layered configuration.
        // For example:
        //   TableConfig implements ReadableConfig {
        //     underlyingLayer ReadableConfig
        //     thisConfigLayer Configuration
        //
        //     get(configOption) {
        //        return thisConfigLayer
        //          .getOptional(configOption)
        //          .orElseGet(underlyingLayer.get(configOption))
        //     }
        //   }
        return configuration;
    }

    // --------------------------------------------------------------------------------------------
    //  Factory methods for ExecutionEnvironments
    // --------------------------------------------------------------------------------------------

    /**
     * Creates an execution environment that represents the context in which the program is
     * currently executed. If the program is invoked standalone, this method returns a local
     * execution environment, as returned by {@link #createLocalEnvironment()}.
     *
     * @return The execution environment of the context in which the program is executed.
     */
    public static StreamExecutionEnvironment getExecutionEnvironment() {
        return getExecutionEnvironment(new Configuration());
    }

    /**
     * Creates an execution environment that represents the context in which the program is
     * currently executed. If the program is invoked standalone, this method returns a local
     * execution environment, as returned by {@link #createLocalEnvironment(Configuration)}.
     *
     * <p>When executed from the command line the given configuration is stacked on top of the
     * global configuration which comes from the {@code config.yaml}, potentially overriding
     * duplicated options.
     *
     * @param configuration The configuration to instantiate the environment with.
     * @return The execution environment of the context in which the program is executed.
     */
    public static StreamExecutionEnvironment getExecutionEnvironment(Configuration configuration) {
        return Utils.resolveFactory(threadLocalContextEnvironmentFactory, contextEnvironmentFactory)
                .map(factory -> factory.createExecutionEnvironment(configuration))
                .orElseGet(() -> StreamExecutionEnvironment.createLocalEnvironment(configuration));
    }

    /**
     * Creates a {@link LocalStreamEnvironment}. The local execution environment will run the
     * program in a multi-threaded fashion in the same JVM as the environment was created in. The
     * default parallelism of the local environment is the number of hardware contexts (CPU cores /
     * threads), unless it was specified differently by {@link #setParallelism(int)}.
     *
     * @return A local execution environment.
     */
    public static LocalStreamEnvironment createLocalEnvironment() {
        return createLocalEnvironment(defaultLocalParallelism);
    }

    /**
     * Creates a {@link LocalStreamEnvironment}. The local execution environment will run the
     * program in a multi-threaded fashion in the same JVM as the environment was created in. It
     * will use the parallelism specified in the parameter.
     *
     * @param parallelism The parallelism for the local environment.
     * @return A local execution environment with the specified parallelism.
     */
    public static LocalStreamEnvironment createLocalEnvironment(int parallelism) {
        return createLocalEnvironment(
                new Configuration().set(CoreOptions.DEFAULT_PARALLELISM, parallelism));
    }

    /**
     * Creates a {@link LocalStreamEnvironment}. The local execution environment will run the
     * program in a multi-threaded fashion in the same JVM as the environment was created in. It
     * will use the parallelism specified in the parameter.
     *
     * @param parallelism The parallelism for the local environment.
     * @param configuration Pass a custom configuration into the cluster
     * @return A local execution environment with the specified parallelism.
     */
    public static LocalStreamEnvironment createLocalEnvironment(
            int parallelism, Configuration configuration) {
        Configuration copyOfConfiguration = new Configuration();
        copyOfConfiguration.addAll(configuration);
        copyOfConfiguration.set(CoreOptions.DEFAULT_PARALLELISM, parallelism);
        return createLocalEnvironment(copyOfConfiguration);
    }

    /**
     * Creates a {@link LocalStreamEnvironment}. The local execution environment will run the
     * program in a multi-threaded fashion in the same JVM as the environment was created in.
     *
     * @param configuration Pass a custom configuration into the cluster
     * @return A local execution environment with the specified parallelism.
     */
    public static LocalStreamEnvironment createLocalEnvironment(Configuration configuration) {
        if (configuration.getOptional(CoreOptions.DEFAULT_PARALLELISM).isPresent()) {
            return new LocalStreamEnvironment(configuration);
        } else {
            Configuration copyOfConfiguration = new Configuration();
            copyOfConfiguration.addAll(configuration);
            copyOfConfiguration.set(CoreOptions.DEFAULT_PARALLELISM, defaultLocalParallelism);
            return new LocalStreamEnvironment(copyOfConfiguration);
        }
    }

    /**
     * Creates a {@link LocalStreamEnvironment} for local program execution that also starts the web
     * monitoring UI.
     *
     * <p>The local execution environment will run the program in a multi-threaded fashion in the
     * same JVM as the environment was created in. It will use the parallelism specified in the
     * parameter.
     *
     * <p>If the configuration key 'rest.port' was set in the configuration, that particular port
     * will be used for the web UI. Otherwise, the default port (8081) will be used.
     */
    @PublicEvolving
    public static StreamExecutionEnvironment createLocalEnvironmentWithWebUI(Configuration conf) {
        checkNotNull(conf, "conf");

        if (!conf.contains(RestOptions.PORT)) {
            // explicitly set this option so that it's not set to 0 later
            conf.set(RestOptions.PORT, RestOptions.PORT.defaultValue());
        }

        return createLocalEnvironment(conf);
    }

    /**
     * Creates a {@link RemoteStreamEnvironment}. The remote environment sends (parts of) the
     * program to a cluster for execution. Note that all file paths used in the program must be
     * accessible from the cluster. The execution will use no parallelism, unless the parallelism is
     * set explicitly via {@link #setParallelism}.
     *
     * @param host The host name or address of the master (JobManager), where the program should be
     *     executed.
     * @param port The port of the master (JobManager), where the program should be executed.
     * @param jarFiles The JAR files with code that needs to be shipped to the cluster. If the
     *     program uses user-defined functions, user-defined input formats, or any libraries, those
     *     must be provided in the JAR files.
     * @return A remote environment that executes the program on a cluster.
     */
    public static StreamExecutionEnvironment createRemoteEnvironment(
            String host, int port, String... jarFiles) {
        return new RemoteStreamEnvironment(host, port, jarFiles);
    }

    /**
     * Creates a {@link RemoteStreamEnvironment}. The remote environment sends (parts of) the
     * program to a cluster for execution. Note that all file paths used in the program must be
     * accessible from the cluster. The execution will use the specified parallelism.
     *
     * @param host The host name or address of the master (JobManager), where the program should be
     *     executed.
     * @param port The port of the master (JobManager), where the program should be executed.
     * @param parallelism The parallelism to use during the execution.
     * @param jarFiles The JAR files with code that needs to be shipped to the cluster. If the
     *     program uses user-defined functions, user-defined input formats, or any libraries, those
     *     must be provided in the JAR files.
     * @return A remote environment that executes the program on a cluster.
     */
    public static StreamExecutionEnvironment createRemoteEnvironment(
            String host, int port, int parallelism, String... jarFiles) {
        RemoteStreamEnvironment env = new RemoteStreamEnvironment(host, port, jarFiles);
        env.setParallelism(parallelism);
        return env;
    }

    /**
     * Creates a {@link RemoteStreamEnvironment}. The remote environment sends (parts of) the
     * program to a cluster for execution. Note that all file paths used in the program must be
     * accessible from the cluster. The execution will use the specified parallelism.
     *
     * @param host The host name or address of the master (JobManager), where the program should be
     *     executed.
     * @param port The port of the master (JobManager), where the program should be executed.
     * @param clientConfig The configuration used by the client that connects to the remote cluster.
     * @param jarFiles The JAR files with code that needs to be shipped to the cluster. If the
     *     program uses user-defined functions, user-defined input formats, or any libraries, those
     *     must be provided in the JAR files.
     * @return A remote environment that executes the program on a cluster.
     */
    public static StreamExecutionEnvironment createRemoteEnvironment(
            String host, int port, Configuration clientConfig, String... jarFiles) {
        return new RemoteStreamEnvironment(host, port, clientConfig, jarFiles);
    }

    /**
     * Gets the default parallelism that will be used for the local execution environment created by
     * {@link #createLocalEnvironment()}.
     *
     * @return The default local parallelism
     */
    @PublicEvolving
    public static int getDefaultLocalParallelism() {
        return defaultLocalParallelism;
    }

    /**
     * Sets the default parallelism that will be used for the local execution environment created by
     * {@link #createLocalEnvironment()}.
     *
     * @param parallelism The parallelism to use as the default local parallelism.
     */
    @PublicEvolving
    public static void setDefaultLocalParallelism(int parallelism) {
        defaultLocalParallelism = parallelism;
    }

    // --------------------------------------------------------------------------------------------
    //  Methods to control the context and local environments for execution from packaged programs
    // --------------------------------------------------------------------------------------------

    protected static void initializeContextEnvironment(StreamExecutionEnvironmentFactory ctx) {
        contextEnvironmentFactory = ctx;
        threadLocalContextEnvironmentFactory.set(ctx);
    }

    protected static void resetContextEnvironment() {
        contextEnvironmentFactory = null;
        threadLocalContextEnvironmentFactory.remove();
    }

    /**
     * Registers a file at the distributed cache under the given name. The file will be accessible
     * from any user-defined function in the (distributed) runtime under a local path. Files may be
     * local files (which will be distributed via BlobServer), or files in a distributed file
     * system. The runtime will copy the files temporarily to a local cache, if needed.
     *
     * <p>The {@link org.apache.flink.api.common.functions.RuntimeContext} can be obtained inside
     * UDFs via {@link org.apache.flink.api.common.functions.RichFunction#getRuntimeContext()} and
     * provides access {@link org.apache.flink.api.common.cache.DistributedCache} via {@link
     * org.apache.flink.api.common.functions.RuntimeContext#getDistributedCache()}.
     *
     * @param filePath The path of the file, as a URI (e.g. "file:///some/path" or
     *     "hdfs://host:port/and/path")
     * @param name The name under which the file is registered.
     */
    public void registerCachedFile(String filePath, String name) {
        registerCachedFile(filePath, name, false);
    }

    /**
     * Registers a file at the distributed cache under the given name. The file will be accessible
     * from any user-defined function in the (distributed) runtime under a local path. Files may be
     * local files (which will be distributed via BlobServer), or files in a distributed file
     * system. The runtime will copy the files temporarily to a local cache, if needed.
     *
     * <p>The {@link org.apache.flink.api.common.functions.RuntimeContext} can be obtained inside
     * UDFs via {@link org.apache.flink.api.common.functions.RichFunction#getRuntimeContext()} and
     * provides access {@link org.apache.flink.api.common.cache.DistributedCache} via {@link
     * org.apache.flink.api.common.functions.RuntimeContext#getDistributedCache()}.
     *
     * @param filePath The path of the file, as a URI (e.g. "file:///some/path" or
     *     "hdfs://host:port/and/path")
     * @param name The name under which the file is registered.
     * @param executable flag indicating whether the file should be executable
     */
    public void registerCachedFile(String filePath, String name, boolean executable) {
        this.cacheFile.add(
                new Tuple2<>(
                        name, new DistributedCache.DistributedCacheEntry(filePath, executable)));
    }

    /**
     * Checks whether it is currently permitted to explicitly instantiate a LocalEnvironment or a
     * RemoteEnvironment.
     *
     * @return True, if it is possible to explicitly instantiate a LocalEnvironment or a
     *     RemoteEnvironment, false otherwise.
     */
    @Internal
    public static boolean areExplicitEnvironmentsAllowed() {
        return contextEnvironmentFactory == null
                && threadLocalContextEnvironmentFactory.get() == null;
    }

    // Private helpers.
    @SuppressWarnings("unchecked")
    private <OUT, T extends TypeInformation<OUT>> T getTypeInfo(
            Object source,
            String sourceName,
            Class<?> baseSourceClass,
            TypeInformation<OUT> typeInfo) {
        TypeInformation<OUT> resolvedTypeInfo = typeInfo;
        if (resolvedTypeInfo == null && source instanceof ResultTypeQueryable) {
            resolvedTypeInfo = ((ResultTypeQueryable<OUT>) source).getProducedType();
        }
        if (resolvedTypeInfo == null) {
            try {
                resolvedTypeInfo =
                        TypeExtractor.createTypeInfo(
                                baseSourceClass, source.getClass(), 0, null, null);
            } catch (final InvalidTypesException e) {
                resolvedTypeInfo = (TypeInformation<OUT>) new MissingTypeInfo(sourceName, e);
            }
        }
        return (T) resolvedTypeInfo;
    }

    @Internal
    public List<Transformation<?>> getTransformations() {
        return transformations;
    }

    @Internal
    public <T> void registerCacheTransformation(
            AbstractID intermediateDataSetID, CacheTransformation<T> t) {
        cachedTransformations.put(intermediateDataSetID, t);
    }

    @Internal
    public void invalidateClusterDataset(AbstractID datasetId) throws Exception {
        if (!cachedTransformations.containsKey(datasetId)) {
            throw new RuntimeException(
                    String.format("IntermediateDataset %s is not found", datasetId));
        }
        final PipelineExecutor executor = getPipelineExecutor();

        if (!(executor instanceof CacheSupportedPipelineExecutor)) {
            return;
        }

        ((CacheSupportedPipelineExecutor) executor)
                .invalidateClusterDataset(datasetId, configuration, userClassloader)
                .get();
        cachedTransformations.get(datasetId).setCached(false);
    }

    protected Set<AbstractID> listCompletedClusterDatasets() {
        try {
            final PipelineExecutor executor = getPipelineExecutor();
            if (!(executor instanceof CacheSupportedPipelineExecutor)) {
                return Collections.emptySet();
            }
            return ((CacheSupportedPipelineExecutor) executor)
                    .listCompletedClusterDatasetIds(configuration, userClassloader)
                    .get();
        } catch (Throwable e) {
            return Collections.emptySet();
        }
    }

    /**
     * Close and clean up the execution environment. All the cached intermediate results will be
     * released physically.
     */
    @Override
    public void close() throws Exception {
        for (AbstractID id : cachedTransformations.keySet()) {
            invalidateClusterDataset(id);
        }
    }

    private PipelineExecutor getPipelineExecutor() throws Exception {
        checkNotNull(
                configuration.get(DeploymentOptions.TARGET),
                "No execution.target specified in your configuration file.");

        final PipelineExecutorFactory executorFactory =
                executorServiceLoader.getExecutorFactory(configuration);

        checkNotNull(
                executorFactory,
                "Cannot find compatible factory for specified execution.target (=%s)",
                configuration.get(DeploymentOptions.TARGET));

        return executorFactory.getExecutor(configuration);
    }
}
