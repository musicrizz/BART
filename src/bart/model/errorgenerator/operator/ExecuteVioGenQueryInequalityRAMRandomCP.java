package bart.model.errorgenerator.operator;

import bart.BartConstants;
import bart.IInitializableOperator;
import bart.OperatorFactory;
import bart.model.EGTask;
import bart.model.VioGenQueryConfiguration;
import bart.model.algebra.IAlgebraOperator;
import bart.model.algebra.Limit;
import bart.model.database.TableAlias;
import bart.model.database.Tuple;
import bart.model.database.operators.IRunQuery;
import bart.model.dependency.ComparisonAtom;
import bart.model.dependency.CrossProductFormulas;
import bart.model.errorgenerator.CellChanges;
import bart.model.errorgenerator.ISampleStrategy;
import bart.model.errorgenerator.SampleParameters;
import bart.model.errorgenerator.VioGenQuery;
import bart.model.errorgenerator.operator.valueselectors.INewValueSelectorStrategy;
import bart.utility.AlgebraUtility;
import bart.utility.BartUtility;
import bart.utility.DependencyUtility;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecuteVioGenQueryInequalityRAMRandomCP implements IVioGenQueryExecutor, IInitializableOperator {

    private static Logger logger = LoggerFactory.getLogger(ExecuteVioGenQueryInequalityRAMRandomCP.class);

    private GenerateChangesAndContexts changesGenerator = new GenerateChangesAndContexts();
    private ISampleStrategy sampleStrategy;
    private IRunQuery queryRunner;
    private INewValueSelectorStrategy valueSelector;

    private void checkConditions(VioGenQuery vioGenQuery, EGTask task) {
        if (!DependencyUtility.hasOnlyVariableInequalities(vioGenQuery.getFormula())) {
            throw new IllegalArgumentException("VioGenQuery with equalities is not supported.\n" + vioGenQuery);
        }
        if (vioGenQuery.getFormula().getCrossProductFormulas().getTableAliasInCrossProducts().size() != 2) {
            throw new IllegalArgumentException("ExecuteVioGenQueryInequalityCrossProducts operator supports only cross products between two queries.\n" + vioGenQuery);
        }
        if (task.getConfiguration().isGenerateAllChanges()) {
            throw new IllegalArgumentException("ExecuteVioGenQueryInequalityCrossProducts requires a random execution. Please set generateAllChanges to false.");
        }
    }

    public void execute(VioGenQuery vioGenQuery, CellChanges allCellChanges, EGTask task) {
        if (task.getConfiguration().isPrintLog()) System.out.println("--- VioGen Query: " + vioGenQuery.toShortString());
        intitializeOperators(task);
        checkConditions(vioGenQuery, task);
        int sampleSize = ExecuteVioGenQueryUtility.computeSampleSize(vioGenQuery, task);
        if (sampleSize == 0) {
            if (task.getConfiguration().isPrintLog()) System.out.println("No changes required");
            return;
        }
        if (!task.getConfiguration().isGenerateAllChanges() && task.getConfiguration().isPrintLog()) {
            System.out.println("Error percentage: " + vioGenQuery.getConfiguration().getPercentage());
            System.out.println(sampleSize + " changes required");
        }
        if (logger.isInfoEnabled()) logger.info("Error percentage: " + vioGenQuery.getConfiguration().getPercentage());
        if (logger.isInfoEnabled()) logger.info(sampleSize + " changes required");
        Set<CrossProductTuplePair> discardedTuples = new HashSet<CrossProductTuplePair>();
        int initialChanges = allCellChanges.getChanges().size();
        Set<Tuple> usedTuples = new HashSet<Tuple>();
        findVioGenQueries(vioGenQuery, allCellChanges, sampleSize, discardedTuples, usedTuples, task);
        if (!ExecuteVioGenQueryUtility.checkIfFinished(allCellChanges, initialChanges, sampleSize)) {
            if (logger.isInfoEnabled()) logger.info("After first iteration there are " + (sampleSize - ((allCellChanges.getChanges().size()) - initialChanges)) + " remaining changes to perform!");
            executeDiscardedPairs(vioGenQuery, allCellChanges, discardedTuples, usedTuples, initialChanges, sampleSize, task);
        }
        int executedChanges = (allCellChanges.getChanges().size()) - initialChanges;
        if (task.getConfiguration().isPrintLog()) System.out.println("Executed changes: " + executedChanges);
    }

    private void findVioGenQueries(VioGenQuery vioGenQuery, CellChanges allCellChanges, int sampleSize, Set<CrossProductTuplePair> discardedTuples, Set<Tuple> usedTuples, EGTask task) {
        int initialChanges = allCellChanges.getChanges().size();
        int offset = computeOffset(vioGenQuery, task);
        CrossProductFormulas crossProduct = vioGenQuery.getFormula().getCrossProductFormulas();
        List<ComparisonAtom> inequalityComparisons = crossProduct.getInequalityComparisons();
        if (logger.isInfoEnabled()) logger.info("Inequality Comparisons: " + inequalityComparisons);
        List<TableAlias> tableAliasInCrossProducts = crossProduct.getTableAliasInCrossProducts();
        TableAlias firstTableAlias = tableAliasInCrossProducts.get(0);
        IAlgebraOperator firstOperator = crossProduct.getCrossProductAlgebraOperator(firstTableAlias);
        firstOperator = addLimit(firstOperator, firstTableAlias, vioGenQuery, offset, task);
        if (logger.isInfoEnabled()) logger.info("First operator\n" + firstOperator);
        List<Tuple> firstExtractedTuples = ExecuteVioGenQueryUtility.materializeTuples(firstOperator, queryRunner, task);
        if (logger.isInfoEnabled()) logger.info("Tuples for alias " + firstTableAlias + ": " + firstExtractedTuples.size());
        TableAlias secondTableAlias = tableAliasInCrossProducts.get(1);
        IAlgebraOperator secondOperator = crossProduct.getCrossProductAlgebraOperator(secondTableAlias);
        secondOperator = addLimit(secondOperator, secondTableAlias, vioGenQuery, offset, task);
        if (logger.isInfoEnabled()) logger.info("Second operator\n" + secondOperator);
        List<Tuple> secondExtractedTuples = ExecuteVioGenQueryUtility.materializeTuples(secondOperator, queryRunner, task);
        if (logger.isInfoEnabled()) logger.info("Tuples for alias " + secondTableAlias + ": " + secondExtractedTuples.size());
        for (Tuple firstTuple : firstExtractedTuples) {
            if (usedTuples.contains(firstTuple)) {
                continue;
            }
            for (Tuple secondTuple : secondExtractedTuples) {
                if (usedTuples.contains(firstTuple) || usedTuples.contains(secondTuple)) {
                    continue;
                }
                CrossProductTuplePair tuplePair = new CrossProductTuplePair(firstTuple, firstTableAlias, secondTuple, secondTableAlias);
                if (discardedTuples.size() < offset) {
                    discardedTuples.add(tuplePair);
                    continue;
                }
                if (!BartUtility.pickRandom(vioGenQuery.getConfiguration().getProbabilityFactorForInequalityQueries())) {
                    discardedTuples.add(tuplePair);
                    continue;
                }
                boolean verified = AlgebraUtility.verifyComparisonsOnTuplePair(tuplePair.getFirstTuple(), tuplePair.getSecondTuple(), vioGenQuery.getFormula(), task);
                if (!verified) {
                    continue;
                }
                if (logger.isInfoEnabled()) logger.info("Tuple pair to handle " + tuplePair);
                changesGenerator.handleTuplePair(tuplePair.getFirstTuple(), tuplePair.getSecondTuple(), vioGenQuery, allCellChanges, usedTuples, valueSelector, task);
                if (ExecuteVioGenQueryUtility.checkIfFinished(allCellChanges, initialChanges, sampleSize)) {
                    if (logger.isInfoEnabled()) logger.info("All changes generated!");
                    return;
                }
            }
        }
    }

    private void executeDiscardedPairs(VioGenQuery vioGenQuery, CellChanges allCellChanges, Set<CrossProductTuplePair> discardedTuples, Set<Tuple> usedTuples, int initialChanges, int sampleSize, EGTask task) {
        Iterator<CrossProductTuplePair> it = discardedTuples.iterator();
        while (it.hasNext()) {
            CrossProductTuplePair tuplePair = it.next();
            if (usedTuples.contains(tuplePair.getFirstTuple()) || usedTuples.contains(tuplePair.getSecondTuple())) {
                continue;
            }
            boolean verified = AlgebraUtility.verifyComparisonsOnTuplePair(tuplePair.getFirstTuple(), tuplePair.getSecondTuple(), vioGenQuery.getFormula(), task);
            if (!verified) {
                continue;
            }
            changesGenerator.handleTuplePair(tuplePair.getFirstTuple(), tuplePair.getSecondTuple(), vioGenQuery, allCellChanges, usedTuples, valueSelector, task);
            if (ExecuteVioGenQueryUtility.checkIfFinished(allCellChanges, initialChanges, sampleSize)) {
                return;
            }
        }
    }

    private int computeOffset(VioGenQuery vioGenQuery, EGTask task) {
        if (!vioGenQuery.getConfiguration().isUseOffsetInInequalityQueries()) {
            return 0;
        }
        int sampleSize = ExecuteVioGenQueryUtility.computeSampleSize(vioGenQuery, task);
        Set<TableAlias> tableInFormula = DependencyUtility.extractTableAliasInFormula(vioGenQuery.getFormula());
        SampleParameters sampleParameters = sampleStrategy.computeParameters(vioGenQuery.getQuery(), tableInFormula, BartConstants.INEQUALITY_QUERY_TYPE, sampleSize, vioGenQuery.getConfiguration(), task);
        int offset = sampleParameters.getOffset();
        if (logger.isInfoEnabled()) logger.info("Offset: " + offset);
        return offset;
    }

    private IAlgebraOperator addLimit(IAlgebraOperator operator, TableAlias tableAlias, VioGenQuery vioGenQuery, int offset, EGTask task) {
        VioGenQueryConfiguration queryConfiguration = vioGenQuery.getConfiguration();
        if (queryConfiguration.isUseLimitInInequalityQueries()) {
            int sampleSize = ExecuteVioGenQueryUtility.computeSampleSize(vioGenQuery, task);
            Set<TableAlias> tablesInFormula = new HashSet<TableAlias>();
            tablesInFormula.add(tableAlias);
            SampleParameters sampleParameters = sampleStrategy.computeParameters(operator, tablesInFormula, BartConstants.INEQUALITY_QUERY_TYPE, sampleSize, queryConfiguration, task);
            int limitValue = sampleParameters.getLimit() + offset;
            Limit limit = new Limit(limitValue);
            limit.addChild(operator);
            operator = limit;
        }
        if (logger.isInfoEnabled()) logger.info("Operator:\n" + operator);
        return operator;
    }

    public void intitializeOperators(EGTask task) {
        queryRunner = OperatorFactory.getInstance().getQueryRunner(task);
        valueSelector = OperatorFactory.getInstance().getValueSelector(task);
        String strategy = task.getConfiguration().getSampleStrategyForInequalityQueries();
        sampleStrategy = OperatorFactory.getInstance().getSampleStrategy(strategy, task);
    }

}

class CrossProductTuplePair {

    Tuple firstTuple;
    TableAlias firstTableAlias;
    Tuple secondTuple;
    TableAlias secondTableAlias;

    public CrossProductTuplePair(Tuple firstTuple, TableAlias firstTableAlias, Tuple secondTuple, TableAlias secondTableAlias) {
        this.firstTuple = firstTuple;
        this.firstTableAlias = firstTableAlias;
        this.secondTuple = secondTuple;
        this.secondTableAlias = secondTableAlias;
    }

    public Tuple getFirstTuple() {
        return firstTuple;
    }

    public TableAlias getFirstTableAlias() {
        return firstTableAlias;
    }

    public Tuple getSecondTuple() {
        return secondTuple;
    }

    public TableAlias getSecondTableAlias() {
        return secondTableAlias;
    }

    @Override
    public String toString() {
        return "CrossProductTuplePair{" + "firstTuple=" + firstTuple.toStringWithOIDAndAlias() + ", firstTableAlias=" + firstTableAlias + "\n\tsecondTuple=" + secondTuple.toStringWithOIDAndAlias() + ", secondTableAlias=" + secondTableAlias + '}';
    }

}