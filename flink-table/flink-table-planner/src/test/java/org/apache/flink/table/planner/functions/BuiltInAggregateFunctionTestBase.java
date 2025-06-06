/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.functions;

import org.apache.flink.client.program.MiniClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.legacy.table.connector.source.SourceFunctionProvider;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.streaming.api.functions.source.legacy.SourceFunction;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableDescriptor;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.expressions.DefaultSqlFactory;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinition;
import org.apache.flink.table.operations.AggregateQueryOperation;
import org.apache.flink.table.operations.ProjectQueryOperation;
import org.apache.flink.table.planner.factories.TableFactoryHarness;
import org.apache.flink.table.planner.functions.BuiltInFunctionTestBase.TestCase;
import org.apache.flink.table.planner.functions.BuiltInFunctionTestBase.TestCaseWithClusterClient;
import org.apache.flink.table.types.DataType;
import org.apache.flink.test.junit5.InjectMiniCluster;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.SerializedThrowable;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.apache.flink.core.testutils.FlinkAssertions.anyCauseMatches;
import static org.apache.flink.runtime.state.StateBackendLoader.HASHMAP_STATE_BACKEND_NAME;
import static org.apache.flink.runtime.state.StateBackendLoader.ROCKSDB_STATE_BACKEND_NAME;
import static org.apache.flink.table.test.TableAssertions.assertThat;
import static org.apache.flink.table.types.DataType.getFieldDataTypes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Test base for testing aggregate {@link BuiltInFunctionDefinition built-in functions}. */
@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BuiltInAggregateFunctionTestBase {

    @RegisterExtension
    public static final MiniClusterExtension MINI_CLUSTER_EXTENSION = new MiniClusterExtension();

    abstract Stream<TestSpec> getTestCaseSpecs();

    final Stream<TestCase> getTestCases() {
        return this.getTestCaseSpecs().flatMap(TestSpec::getTestCases);
    }

    @ParameterizedTest
    @MethodSource("getTestCases")
    final void test(TestCase testCase, @InjectMiniCluster MiniCluster miniCluster)
            throws Throwable {
        testCase.execute(new MiniClusterClient(miniCluster.getConfiguration(), miniCluster));
    }

    protected static Table asTable(TableEnvironment tEnv, DataType sourceRowType, List<Row> rows) {
        final TableDescriptor descriptor =
                TableFactoryHarness.newBuilder()
                        .schema(Schema.newBuilder().fromRowDataType(sourceRowType).build())
                        .source(asSource(rows, sourceRowType))
                        .build();

        return tEnv.from(descriptor);
    }

    protected static TableFactoryHarness.ScanSourceBase asSource(
            List<Row> rows, DataType producedDataType) {
        return new TableFactoryHarness.ScanSourceBase() {
            @Override
            public ChangelogMode getChangelogMode() {
                final Set<RowKind> rowKinds =
                        rows.stream().map(Row::getKind).collect(Collectors.toSet());
                if (rowKinds.size() == 1 && rowKinds.contains(RowKind.INSERT)) {
                    return ChangelogMode.insertOnly();
                }

                return ChangelogMode.all();
            }

            @Override
            public ScanRuntimeProvider getScanRuntimeProvider(ScanContext context) {
                final DataStructureConverter converter =
                        context.createDataStructureConverter(producedDataType);

                return SourceFunctionProvider.of(new Source(rows, converter), true);
            }
        };
    }

    private static List<Row> materializeResult(TableResult tableResult) {
        try (final CloseableIterator<Row> iterator = tableResult.collect()) {
            final List<Row> actualRows = new ArrayList<>();
            iterator.forEachRemaining(
                    row -> {
                        final RowKind kind = row.getKind();
                        switch (kind) {
                            case INSERT:
                            case UPDATE_AFTER:
                                row.setKind(RowKind.INSERT);
                                actualRows.add(row);
                                break;
                            case UPDATE_BEFORE:
                            case DELETE:
                                row.setKind(RowKind.INSERT);
                                actualRows.remove(row);
                                break;
                        }
                    });

            return actualRows;
        } catch (Exception e) {
            throw new RuntimeException("Could not collect results", e);
        }
    }

    // ---------------------------------------------------------------------------------------------

    protected static final class TableApiAggSpec {
        private final List<Expression> selectExpr;
        private final List<Expression> groupByExpr;

        public TableApiAggSpec(List<Expression> selectExpr, List<Expression> groupByExpr) {
            this.selectExpr = selectExpr;
            this.groupByExpr = groupByExpr;
        }

        public static TableApiAggSpec groupBySelect(
                List<Expression> groupByExpr, Expression... selectExpr) {
            return new TableApiAggSpec(
                    Arrays.stream(selectExpr).collect(Collectors.toList()), groupByExpr);
        }

        public static TableApiAggSpec select(Expression... selectExpr) {
            return new TableApiAggSpec(
                    Arrays.stream(selectExpr).collect(Collectors.toList()), null);
        }
    }

    /** Test specification. */
    protected static class TestSpec {

        private final @Nullable BuiltInFunctionDefinition definition;
        private final List<TestItem> testItems = new ArrayList<>();

        private @Nullable String description;

        private DataType sourceRowType;
        private List<Row> sourceRows;

        private TestSpec(BuiltInFunctionDefinition definition) {
            this.definition = definition;
        }

        static TestSpec forFunction(BuiltInFunctionDefinition definition) {
            return new TestSpec(definition);
        }

        static TestSpec forExpression(String expr) {
            return new TestSpec(null).withDescription(expr);
        }

        TestSpec withDescription(String description) {
            this.description = description;
            return this;
        }

        TestSpec withSource(DataType sourceRowType, List<Row> sourceRows) {
            this.sourceRowType = sourceRowType;
            this.sourceRows = sourceRows;
            return this;
        }

        TestSpec testSqlResult(
                Function<Table, String> sqlSpec, DataType expectedRowType, List<Row> expectedRows) {
            this.testItems.add(new SqlTestItem(sqlSpec, expectedRowType, expectedRows));
            return this;
        }

        TestSpec testApiResult(
                List<Expression> selectExpr,
                List<Expression> groupByExpr,
                DataType expectedRowType,
                List<Row> expectedRows) {
            this.testItems.add(
                    new TableApiTestItem(selectExpr, groupByExpr, expectedRowType, expectedRows));
            return this;
        }

        TestSpec testApiSqlResult(
                List<Expression> selectExpr,
                List<Expression> groupByExpr,
                DataType expectedRowType,
                List<Row> expectedRows) {
            this.testItems.add(
                    new TableApiSqlResultTestItem(
                            selectExpr, groupByExpr, expectedRowType, expectedRows));
            return this;
        }

        TestSpec testResult(
                Function<Table, String> sqlSpec,
                TableApiAggSpec tableApiSpec,
                DataType expectedRowType,
                List<Row> expectedRows) {
            return testResult(
                    sqlSpec, tableApiSpec, expectedRowType, expectedRowType, expectedRows);
        }

        TestSpec testResult(
                Function<Table, String> sqlSpec,
                TableApiAggSpec tableApiSpec,
                DataType expectedSqlRowType,
                DataType expectedTableApiRowType,
                List<Row> expectedRows) {
            testSqlResult(sqlSpec, expectedSqlRowType, expectedRows);
            testApiResult(
                    tableApiSpec.selectExpr,
                    tableApiSpec.groupByExpr,
                    expectedTableApiRowType,
                    expectedRows);
            testApiSqlResult(
                    tableApiSpec.selectExpr,
                    tableApiSpec.groupByExpr,
                    expectedSqlRowType,
                    expectedRows);
            return this;
        }

        TestSpec testSqlValidationError(Function<Table, String> sqlSpec, String errorMessage) {
            this.testItems.add(
                    new SqlErrorTestItem(
                            sqlSpec, null, ValidationException.class, errorMessage, true));
            return this;
        }

        TestSpec testTableApiValidationError(TableApiAggSpec tableApiSpec, String errorMessage) {
            this.testItems.add(
                    new TableApiErrorTestItem(
                            tableApiSpec.selectExpr,
                            tableApiSpec.groupByExpr,
                            null,
                            ValidationException.class,
                            errorMessage,
                            true));
            return this;
        }

        TestSpec testValidationError(
                Function<Table, String> sqlSpec,
                TableApiAggSpec tableApiSpec,
                String errorMessage) {
            testSqlValidationError(sqlSpec, errorMessage);
            testTableApiValidationError(tableApiSpec, errorMessage);
            return this;
        }

        TestSpec testSqlRuntimeError(
                Function<Table, String> sqlSpec,
                DataType expectedRowType,
                Class<? extends Throwable> errorClass,
                String errorMessage) {
            this.testItems.add(
                    new SqlErrorTestItem(
                            sqlSpec, expectedRowType, errorClass, errorMessage, false));
            return this;
        }

        private TestCaseWithClusterClient createTestItemExecutable(
                TestItem testItem, String stateBackend) {
            return (clusterClient) -> {
                Configuration conf = new Configuration();
                conf.set(StateBackendOptions.STATE_BACKEND, stateBackend);
                final TableEnvironment tEnv =
                        TableEnvironment.create(
                                EnvironmentSettings.newInstance()
                                        .inStreamingMode()
                                        .withConfiguration(conf)
                                        .build());
                final Table sourceTable = asTable(tEnv, sourceRowType, sourceRows);

                testItem.execute(tEnv, sourceTable, clusterClient);
            };
        }

        Stream<TestCase> getTestCases() {
            return Stream.concat(
                    testItems.stream()
                            .map(
                                    testItem ->
                                            new TestCase(
                                                    testItem.toString(),
                                                    createTestItemExecutable(
                                                            testItem, HASHMAP_STATE_BACKEND_NAME))),
                    testItems.stream()
                            .map(
                                    testItem ->
                                            new TestCase(
                                                    testItem.toString(),
                                                    createTestItemExecutable(
                                                            testItem,
                                                            ROCKSDB_STATE_BACKEND_NAME))));
        }

        @Override
        public String toString() {
            final StringBuilder bob = new StringBuilder();
            if (definition != null) {
                bob.append(definition.getName());
            }
            if (description != null) {
                bob.append(" (");
                bob.append(description);
                bob.append(")");
            }

            return bob.toString();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private interface TestItem {
        void execute(TableEnvironment tEnv, Table sourceTable, MiniClusterClient clusterClient);
    }

    // ---------------------------------------------------------------------------------------------

    private abstract static class SuccessItem implements TestItem {
        private final @Nullable DataType expectedRowType;
        private final @Nullable List<Row> expectedRows;

        public SuccessItem(@Nullable DataType expectedRowType, @Nullable List<Row> expectedRows) {
            this.expectedRowType = expectedRowType;
            this.expectedRows = expectedRows;
        }

        @Override
        public void execute(
                TableEnvironment tEnv, Table sourceTable, MiniClusterClient clusterClient) {
            final TableResult tableResult = getResult(tEnv, sourceTable);

            if (expectedRowType != null) {
                final DataType actualRowType =
                        tableResult.getResolvedSchema().toSourceRowDataType();

                assertThat(actualRowType)
                        .getChildren()
                        .containsExactlyElementsOf(getFieldDataTypes(expectedRowType));
            }

            if (expectedRows != null) {
                final List<Row> actualRows = materializeResult(tableResult);

                assertThat(actualRows).containsExactlyInAnyOrderElementsOf(expectedRows);
            }
        }

        protected abstract TableResult getResult(TableEnvironment tEnv, Table sourceTable);
    }

    private static class SqlTestItem extends SuccessItem {
        private final Function<Table, String> spec;

        public SqlTestItem(
                Function<Table, String> spec,
                @Nullable DataType expectedRowType,
                @Nullable List<Row> expectedRows) {
            super(expectedRowType, expectedRows);
            this.spec = spec;
        }

        @Override
        protected TableResult getResult(TableEnvironment tEnv, Table sourceTable) {
            return tEnv.sqlQuery(spec.apply(sourceTable)).execute();
        }
    }

    private static class TableApiTestItem extends SuccessItem {
        private final List<Expression> selectExpr;
        private final List<Expression> groupByExpr;

        public TableApiTestItem(
                List<Expression> selectExpr,
                @Nullable List<Expression> groupByExpr,
                @Nullable DataType expectedRowType,
                @Nullable List<Row> expectedRows) {
            super(expectedRowType, expectedRows);
            this.selectExpr = selectExpr;
            this.groupByExpr = groupByExpr;
        }

        @Override
        protected TableResult getResult(TableEnvironment tEnv, Table sourceTable) {
            if (groupByExpr != null) {
                return sourceTable
                        .groupBy(groupByExpr.toArray(new Expression[0]))
                        .select(selectExpr.toArray(new Expression[0]))
                        .execute();
            } else {
                return sourceTable.select(selectExpr.toArray(new Expression[0])).execute();
            }
        }
    }

    private static class TableApiSqlResultTestItem extends SuccessItem {

        private final List<Expression> selectExpr;
        private final List<Expression> groupByExpr;

        public TableApiSqlResultTestItem(
                List<Expression> selectExpr,
                @Nullable List<Expression> groupByExpr,
                @Nullable DataType expectedRowType,
                @Nullable List<Row> expectedRows) {
            super(expectedRowType, expectedRows);
            this.selectExpr = selectExpr;
            this.groupByExpr = groupByExpr;
        }

        @Override
        protected TableResult getResult(TableEnvironment tEnv, Table sourceTable) {
            final Table select;
            if (groupByExpr != null) {
                select =
                        sourceTable
                                .groupBy(groupByExpr.toArray(new Expression[0]))
                                .select(selectExpr.toArray(new Expression[0]));

            } else {
                select = sourceTable.select(selectExpr.toArray(new Expression[0]));
            }
            final ProjectQueryOperation projectQueryOperation =
                    (ProjectQueryOperation) select.getQueryOperation();
            final AggregateQueryOperation aggQueryOperation =
                    (AggregateQueryOperation) select.getQueryOperation().getChildren().get(0);

            final List<ResolvedExpression> selectExpr =
                    recreateSelectList(aggQueryOperation, projectQueryOperation);

            final String selectAsSerializableString = toSerializableExpr(selectExpr);
            final String groupByAsSerializableString =
                    toSerializableExpr(aggQueryOperation.getGroupingExpressions());

            final StringBuilder stringBuilder = new StringBuilder();
            stringBuilder
                    .append("SELECT ")
                    .append(selectAsSerializableString)
                    .append(" FROM ")
                    .append(sourceTable);
            if (!groupByAsSerializableString.isEmpty()) {
                stringBuilder.append(" GROUP BY ").append(groupByAsSerializableString);
            }

            return tEnv.sqlQuery(stringBuilder.toString()).execute();
        }

        private static List<ResolvedExpression> recreateSelectList(
                AggregateQueryOperation aggQueryOperation,
                ProjectQueryOperation projectQueryOperation) {
            final List<String> projectSchemaFields =
                    projectQueryOperation.getResolvedSchema().getColumnNames();
            final List<String> aggSchemaFields =
                    aggQueryOperation.getResolvedSchema().getColumnNames();
            return IntStream.range(0, projectSchemaFields.size())
                    .mapToObj(
                            idx -> {
                                final int indexInAgg =
                                        aggSchemaFields.indexOf(projectSchemaFields.get(idx));
                                if (indexInAgg >= 0) {
                                    final int groupingExprCount =
                                            aggQueryOperation.getGroupingExpressions().size();
                                    if (indexInAgg < groupingExprCount) {
                                        return aggQueryOperation
                                                .getGroupingExpressions()
                                                .get(indexInAgg);
                                    } else {
                                        return aggQueryOperation
                                                .getAggregateExpressions()
                                                .get(indexInAgg - groupingExprCount);
                                    }
                                } else {
                                    return projectQueryOperation.getProjectList().get(idx);
                                }
                            })
                    .collect(Collectors.toList());
        }

        private static String toSerializableExpr(List<ResolvedExpression> expressions) {
            return expressions.stream()
                    .map(
                            resolvedExpression ->
                                    resolvedExpression.asSerializableString(
                                            DefaultSqlFactory.INSTANCE))
                    .collect(Collectors.joining(", "));
        }

        @Override
        public String toString() {
            return String.format(
                    "[API as SQL] select: [%s] groupBy: [%s]",
                    selectExpr.stream()
                            .map(Expression::asSummaryString)
                            .collect(Collectors.joining(", ")),
                    groupByExpr != null
                            ? groupByExpr.stream()
                                    .map(Expression::asSummaryString)
                                    .collect(Collectors.joining(", "))
                            : "");
        }
    }

    // ---------------------------------------------------------------------------------------------

    private abstract static class ErrorTestItem implements TestItem {
        private final DataType expectedRowType;
        private final Class<? extends Throwable> errorClass;
        private final String errorMessage;
        private final boolean expectedDuringValidation;

        public ErrorTestItem(
                @Nullable DataType expectedRowType,
                @Nullable Class<? extends Throwable> errorClass,
                @Nullable String errorMessage,
                boolean expectedDuringValidation) {
            Preconditions.checkState(errorClass != null || errorMessage != null);
            this.expectedRowType = expectedRowType;
            this.errorClass = errorClass;
            this.errorMessage = errorMessage;
            this.expectedDuringValidation = expectedDuringValidation;
        }

        Consumer<? super Throwable> errorMatcher() {
            if (errorClass != null && errorMessage != null) {
                return anyCauseMatches(errorClass, errorMessage);
            }
            if (errorMessage != null) {
                return anyCauseMatches(errorMessage);
            }
            return anyCauseMatches(errorClass);
        }

        @Override
        public void execute(
                TableEnvironment tEnv, Table sourceTable, MiniClusterClient clusterClient) {
            AtomicReference<TableResult> tableResult = new AtomicReference<>();

            Throwable t =
                    catchThrowable(() -> tableResult.set(this.query(tEnv, sourceTable).execute()));

            if (this.expectedDuringValidation) {
                assertThat(t)
                        .as("Expected a validation exception")
                        .isNotNull()
                        .satisfies(this.errorMatcher());
                return;
            } else {
                assertThat(t).as("Error while validating the query").isNull();
            }

            if (expectedRowType != null) {
                final DataType actualRowType =
                        tableResult.get().getResolvedSchema().toSourceRowDataType();

                assertThat(actualRowType)
                        .getChildren()
                        .containsExactlyElementsOf(getFieldDataTypes(expectedRowType));
            }

            assertThatThrownBy(() -> runAndWaitForFailure(clusterClient, tableResult))
                    .isNotNull()
                    .satisfies(this.errorMatcher());
        }

        private void runAndWaitForFailure(
                final MiniClusterClient clusterClient,
                final AtomicReference<TableResult> tableResult)
                throws Throwable {
            final TableResult result = tableResult.get();
            result.await();
            final Optional<SerializedThrowable> serializedThrowable =
                    clusterClient
                            .requestJobResult(result.getJobClient().get().getJobID())
                            .get()
                            .getSerializedThrowable();
            if (serializedThrowable.isPresent()) {
                throw serializedThrowable.get().deserializeError(getClass().getClassLoader());
            }
        }

        protected abstract Table query(TableEnvironment tEnv, @Nullable Table inputTable);
    }

    private static final class SqlErrorTestItem extends ErrorTestItem {
        private final Function<Table, String> spec;

        public SqlErrorTestItem(
                Function<Table, String> spec,
                @Nullable DataType expectedRowType,
                Class<? extends Throwable> errorClass,
                String errorMessage,
                boolean expectedDuringValidation) {
            super(expectedRowType, errorClass, errorMessage, expectedDuringValidation);
            this.spec = spec;
        }

        @Override
        protected Table query(TableEnvironment tEnv, Table sourceTable) {
            return tEnv.sqlQuery(spec.apply(sourceTable));
        }
    }

    private static final class TableApiErrorTestItem extends ErrorTestItem {
        private final List<Expression> selectExpr;
        private final List<Expression> groupByExpr;

        public TableApiErrorTestItem(
                List<Expression> selectExpr,
                List<Expression> groupByExpr,
                DataType expectedRowType,
                Class<? extends Throwable> errorClass,
                String errorMessage,
                boolean expectedDuringValidation) {
            super(expectedRowType, errorClass, errorMessage, expectedDuringValidation);
            this.selectExpr = selectExpr;
            this.groupByExpr = groupByExpr;
        }

        @Override
        protected Table query(TableEnvironment tEnv, Table sourceTable) {
            if (groupByExpr != null) {
                return sourceTable
                        .groupBy(groupByExpr.toArray(new Expression[0]))
                        .select(selectExpr.toArray(new Expression[0]));
            } else {
                return sourceTable.select(selectExpr.toArray(new Expression[0]));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static class Source implements SourceFunction<RowData> {

        private final List<Row> rows;
        private final DynamicTableSource.DataStructureConverter converter;

        public Source(List<Row> rows, DynamicTableSource.DataStructureConverter converter) {
            this.rows = rows;
            this.converter = converter;
        }

        @Override
        public void run(SourceContext<RowData> ctx) throws Exception {
            rows.stream().map(row -> (RowData) converter.toInternal(row)).forEach(ctx::collect);
        }

        @Override
        public void cancel() {}
    }
}
