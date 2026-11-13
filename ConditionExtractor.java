package analyser.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the AST of a Java source file and extracts every decision point
 * along with the individual conditions inside each decision.
 *
 * A DECISION  = the full boolean expression inside if / while / for
 * A CONDITION = an atomic boolean that cannot be split further (no && or ||)
 */
public class ConditionExtractor {

    // ── Data classes ──────────────────────────────────────────────────────────

    /** One atomic condition inside a decision */
    public static class Condition {
        public final String expression;
        public final int    line;

        public Condition(String expression, int line) {
            this.expression = expression;
            this.line       = line;
        }
    }

    /** One decision point (if / while / for) and its conditions */
    public static class Decision {
        public final String          type;       // "if", "while", "for"
        public final String          fullExpr;   // the whole boolean expression
        public final int             line;       // source line
        public final List<Condition> conditions; // atomic conditions inside it

        public Decision(String type, String fullExpr, int line) {
            this.type       = type;
            this.fullExpr   = fullExpr;
            this.line       = line;
            this.conditions = new ArrayList<>();
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parse sourceCode and return every decision found.
     */
    public List<Decision> extract(String sourceCode) {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);//converts source code to AST
        List<Decision> decisions = new ArrayList<>();
        new DecisionVisitor(decisions).visit(cu, null);//walks through tree
        return decisions;//display everything found
    }

    // ── AST Visitor ───────────────────────────────────────────────────────────

    private static class DecisionVisitor extends VoidVisitorAdapter<Void> {

        private final List<Decision> decisions;
        private int nestingLevel = 0;
        DecisionVisitor(List<Decision> decisions) {
            this.decisions = decisions;
        }

        @Override
        public void visit(IfStmt n, Void arg) {
            Expression condition = n.getCondition();
            int line = n.getBegin().map(p -> p.line).orElse(-1);
            Decision d = new Decision("if", condition.toString(), line);
            extractConditions(condition, d.conditions);
            decisions.add(d);
            super.visit(n, arg);
        }

        @Override
        public void visit(WhileStmt n, Void arg) {
            Expression condition = n.getCondition();
            int line = n.getBegin().map(p -> p.line).orElse(-1);
            Decision d = new Decision("while", condition.toString(), line);
            extractConditions(condition, d.conditions);
            decisions.add(d);
            super.visit(n, arg);
        }

        @Override
        public void visit(ForStmt n, Void arg) {
            n.getCompare().ifPresent(condition -> {
                int line = n.getBegin().map(p -> p.line).orElse(-1);
                Decision d = new Decision("for", condition.toString(), line);
                extractConditions(condition, d.conditions);
                decisions.add(d);
            });
            super.visit(n, arg);
        }
    }

    // ── Condition splitter ────────────────────────────────────────────────────

    private static void extractConditions(Expression expr, List<Condition> out) {

        if (expr instanceof BinaryExpr) {
            BinaryExpr binary = (BinaryExpr) expr;
            BinaryExpr.Operator op = binary.getOperator();
            if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
                extractConditions(binary.getLeft(),  out);
                extractConditions(binary.getRight(), out);
                return;
            }
        }

        if (expr instanceof EnclosedExpr) {
            extractConditions(((EnclosedExpr) expr).getInner(), out);
            return;
        }

        int line = expr.getBegin().map(p -> p.line).orElse(-1);
        out.add(new Condition(expr.toString(), line));
    }
}