package analyser.mcdc;

import analyser.parser.ConditionExtractor.Decision;
import java.util.*;

public class McdcEngine {

    // ── Data classes ──────────────────────────────────────────────────────────

    public static class TestCase {
        public final String              id;
        public final List<Boolean>       inputs;
        public final boolean             output;
        public final String              conditionTested;
        public final String              nodeId;
        public final String              explanation;

        public TestCase(String id, List<Boolean> inputs, boolean output,
                        String conditionTested, String nodeId, String explanation) {
            this.id               = id;
            this.inputs           = inputs;
            this.output           = output;
            this.conditionTested  = conditionTested;
            this.nodeId           = nodeId;
            this.explanation      = explanation;
        }
    }

    public static class McdcResult {
        public final List<TestCase> testCases;
        public final int            total;
        public final int            covered;
        public final double         percentage;

        public McdcResult(List<TestCase> testCases, int total, int covered) {
            this.testCases  = testCases;
            this.total      = total;
            this.covered    = covered;
            this.percentage = total == 0 ? 0 : (covered * 100.0 / total);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public McdcResult analyse(Decision decision, String decisionNodeId) {
        int n = decision.conditions.size();

        // Single condition — only need true and false
        if (n == 1) {
            List<TestCase> tcs = new ArrayList<>();
            tcs.add(new TestCase("TC1", List.of(true),  evaluate(List.of(true),  decision), null, null, null));
            tcs.add(new TestCase("TC2", List.of(false), evaluate(List.of(false), decision), null, null, null));
            return new McdcResult(tcs, 1, 1);
        }

        // Generate full truth table
        List<List<Boolean>> table = generateTruthTable(n);

        // Find independence pairs for each condition
        List<TestCase> testCases   = new ArrayList<>();
        Set<Integer>   selectedRows = new LinkedHashSet<>();
        int            covered      = 0;

        for (int condIdx = 0; condIdx < n; condIdx++) {
            int[] pair = findIndependencePair(table, decision, condIdx);

            if (pair != null) {
                selectedRows.add(pair[0]);
                selectedRows.add(pair[1]);
                covered++;
            }
        }

        // Build test cases from selected rows
        int tcNum = 1;
        Map<Integer, String> rowToTcId = new HashMap<>();
        for (int row : selectedRows) {
            String tcId = "TC" + tcNum++;
            rowToTcId.put(row, tcId);
            List<Boolean> inputs = table.get(row);
            boolean output = evaluate(inputs, decision);
            testCases.add(new TestCase(tcId, inputs, output, null, null, null));
        }

        // Add explanations for each independence pair
        List<TestCase> finalCases = new ArrayList<>();
        Set<Integer> explained = new HashSet<>();

        for (int condIdx = 0; condIdx < n; condIdx++) {
            int[] pair = findIndependencePair(table, decision, condIdx);
            if (pair == null) continue;

            String condName = decision.conditions.get(condIdx).expression;
            String tcA = rowToTcId.get(pair[0]);
            String tcB = rowToTcId.get(pair[1]);
            String explanation = tcA + " vs " + tcB + ": condition '" + condName +
                "' changes while all others remain constant. " +
                "The result changes, proving independence. MC/DC satisfied.";

            for (TestCase tc : testCases) {
                if (tc.id.equals(tcA) && !explained.contains(pair[0])) {
                    finalCases.add(new TestCase(tc.id, tc.inputs, tc.output,
                        condName, decisionNodeId, explanation));
                    explained.add(pair[0]);
                } else if (tc.id.equals(tcB) && !explained.contains(pair[1])) {
                    finalCases.add(new TestCase(tc.id, tc.inputs, tc.output,
                        condName, decisionNodeId, explanation));
                    explained.add(pair[1]);
                }
            }
        }

        // Add any remaining test cases without explanation
        for (TestCase tc : testCases) {
            if (!explained.contains(rowToTcId.entrySet().stream()
                    .filter(e -> e.getValue().equals(tc.id))
                    .map(Map.Entry::getKey).findFirst().orElse(-1))) {
                finalCases.add(tc);
            }
        }

        return new McdcResult(finalCases, n, covered);
    }

    // ── Truth table generator ─────────────────────────────────────────────────

    private List<List<Boolean>> generateTruthTable(int n) {
        List<List<Boolean>> table = new ArrayList<>();
        int rows = (int) Math.pow(2, n);
        for (int i = 0; i < rows; i++) {
            List<Boolean> row = new ArrayList<>();
            for (int j = n - 1; j >= 0; j--) {
                row.add(((i >> j) & 1) == 1);
            }
            table.add(row);
        }
        return table;
    }

    // ── Independence pair finder ──────────────────────────────────────────────

    private int[] findIndependencePair(List<List<Boolean>> table,
                                        Decision decision, int condIdx) {
        for (int a = 0; a < table.size(); a++) {
            for (int b = a + 1; b < table.size(); b++) {
                List<Boolean> rowA = table.get(a);
                List<Boolean> rowB = table.get(b);

                // Only the target condition differs
                boolean onlyTargetDiffers = true;
                for (int k = 0; k < rowA.size(); k++) {
                    if (k == condIdx) {
                        if (rowA.get(k).equals(rowB.get(k))) {
                            onlyTargetDiffers = false;
                            break;
                        }
                    } else {
                        if (!rowA.get(k).equals(rowB.get(k))) {
                            onlyTargetDiffers = false;
                            break;
                        }
                    }
                }

                if (!onlyTargetDiffers) continue;

                // Result must differ
                boolean outA = evaluate(rowA, decision);
                boolean outB = evaluate(rowB, decision);
                if (outA != outB) return new int[]{a, b};
            }
        }
        return null;
    }

    // ── Expression evaluator ──────────────────────────────────────────────────

    private boolean evaluate(List<Boolean> inputs, Decision decision) {
        // Build a simple map: condition expression → boolean value
        Map<String, Boolean> values = new HashMap<>();
        for (int i = 0; i < decision.conditions.size(); i++) {
            values.put(decision.conditions.get(i).expression, inputs.get(i));
        }
        return evaluateExpr(decision.fullExpr, values);
    }

    private boolean evaluateExpr(String expr, Map<String, Boolean> values) {
        expr = expr.trim();

        // Strip outer parentheses
        while (expr.startsWith("(") && expr.endsWith(")") && matchingParen(expr)) {
            expr = expr.substring(1, expr.length() - 1).trim();
        }

        // Find top-level || first (lowest precedence)
        int idx = findTopLevelOperator(expr, "||");
        if (idx >= 0) {
            return evaluateExpr(expr.substring(0, idx), values) ||
                   evaluateExpr(expr.substring(idx + 2), values);
        }

        // Then top-level &&
        idx = findTopLevelOperator(expr, "&&");
        if (idx >= 0) {
            return evaluateExpr(expr.substring(0, idx), values) &&
                   evaluateExpr(expr.substring(idx + 2), values);
        }

        // Atomic condition — look up in values map
        Boolean val = values.get(expr);
        return val != null && val;
    }

    private int findTopLevelOperator(String expr, String op) {
        int depth = 0;
        for (int i = 0; i < expr.length() - op.length() + 1; i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && expr.startsWith(op, i)) return i;
        }
        return -1;
    }

    private boolean matchingParen(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            if (expr.charAt(i) == '(') depth++;
            else if (expr.charAt(i) == ')') depth--;
            if (depth == 0 && i < expr.length() - 1) return false;
        }
        return depth == 0;
    }
}