package analyser.cfg;

import analyser.parser.ConditionExtractor.Decision;
import java.util.*;

public class CfgBuilder {

    // ── Data classes (these stay the same) ──────────────────────────────────

    public static class CfgNode {
        public final String id;
        public final String type;   // entry, exit, decision, stmt, merge
        public final String label;
        public int x, y;            // coordinates for the frontend

        public CfgNode(String id, String type, String label) {
            this.id = id;
            this.type = type;
            this.label = label;
        }
    }

    public static class CfgEdge {
        public final String from;
        public final String to;
        public final String label;  // "true", "false", or ""

        public CfgEdge(String from, String to, String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }

    public static class Cfg {
        public final List<CfgNode> nodes = new ArrayList<>();
        public final List<CfgEdge> edges = new ArrayList<>();
    }

    // ── Main build method ─────────────────────────────────────────────────────

    public Cfg build(List<Decision> decisions) {
        Cfg cfg = new Cfg();

        // STEP 1: Create ENTRY node (start of the method)
        CfgNode entry = new CfgNode("entry", "entry", "ENTRY");
        cfg.nodes.add(entry);

        // STEP 2: Create EXIT node (end of the method)
        // We'll add this later, but create it now
        CfgNode exit = new CfgNode("exit", "exit", "EXIT");

        // STEP 3: Handle the case where there are NO decisions
        // Just connect ENTRY → EXIT
        if (decisions.isEmpty()) {
            cfg.nodes.add(exit);
            cfg.edges.add(new CfgEdge("entry", "exit", ""));
            assignCoordinates(cfg);
            return cfg;
        }

        // STEP 4: Keep track of the "previous" node for connecting
        // Start with ENTRY as the previous node
        String previousId = "entry";

        // STEP 5: Loop through each decision
        for (int i = 0; i < decisions.size(); i++) {
            Decision d = decisions.get(i);

            // Create unique IDs for this decision
            String decId = "d" + i;           // e.g., "d0", "d1", "d2"
            String trueId = "s" + i + "_true";   // e.g., "s0_true"
            String falseId = "s" + i + "_false"; // e.g., "s0_false"
            String mergeId = "m" + i;          // e.g., "m0", "m1"

            // ── Create DECISION node ──
            // This is the conditional branch
            CfgNode decNode = new CfgNode(decId, "decision", d.fullExpr);
            cfg.nodes.add(decNode);

            // Connect previous node → this decision
            cfg.edges.add(new CfgEdge(previousId, decId, ""));
            // Note: "" means no label (normal flow)

            // ── Create TRUE branch node ──
            // This represents what happens when condition is true
            CfgNode trueNode = new CfgNode(trueId, "stmt", "TRUE branch");
            cfg.nodes.add(trueNode);
            cfg.edges.add(new CfgEdge(decId, trueId, "true"));

            // ── Create FALSE branch node ──
            // This represents what happens when condition is false
            CfgNode falseNode = new CfgNode(falseId, "stmt", "FALSE branch");
            cfg.nodes.add(falseNode);
            cfg.edges.add(new CfgEdge(decId, falseId, "false"));

            // ── Create MERGE node ──
            // This is where both branches come back together
            CfgNode mergeNode = new CfgNode(mergeId, "merge", "MERGE");
            cfg.nodes.add(mergeNode);

            // Both branches go to the MERGE node
            cfg.edges.add(new CfgEdge(trueId, mergeId, ""));
            cfg.edges.add(new CfgEdge(falseId, mergeId, ""));

            // ── Connect MERGE to the next decision or EXIT ──
            if (i == decisions.size() - 1) {
                // This is the LAST decision → connect MERGE to EXIT
                cfg.edges.add(new CfgEdge(mergeId, "exit", ""));
            }
            // If NOT the last decision, previousId will be set below
            // and the NEXT iteration will connect from MERGE to the next decision

            // STEP 6: Update previousId to this merge node
            // This ensures the next decision connects AFTER this one
            previousId = mergeId;
        }

        // STEP 7: Add EXIT node (we created it earlier)
        cfg.nodes.add(exit);

        // STEP 8: Assign coordinates for the frontend visualization
        assignCoordinates(cfg);

        return cfg;
    }

    // ── Coordinate assignment ────────────────────────────────────────────────

    private void assignCoordinates(Cfg cfg) {
        // Create a map to easily find nodes by ID
        Map<String, CfgNode> nodeMap = new HashMap<>();
        for (CfgNode n : cfg.nodes) {
            nodeMap.put(n.id, n);
        }

        // ── ENTRY node ──
        CfgNode entry = nodeMap.get("entry");
        if (entry != null) {
            entry.x = 0;
            entry.y = -220;
        }

        // ── Find all decision nodes ──
        List<CfgNode> decisions = new ArrayList<>();
        for (CfgNode n : cfg.nodes) {
            if ("decision".equals(n.type)) {
                decisions.add(n);
            }
        }

        // ── Layout decisions vertically ──
        int yStart = -130;
        int yStep = 120;

        for (int i = 0; i < decisions.size(); i++) {
            CfgNode dec = decisions.get(i);
            int idx = Integer.parseInt(dec.id.substring(1)); // "d0" → 0, "d1" → 1

            // Decision in the center
            dec.x = 0;
            dec.y = yStart + (idx * yStep * 2);

            // True branch on the LEFT
            CfgNode trueNode = nodeMap.get("s" + idx + "_true");
            if (trueNode != null) {
                trueNode.x = -160;
                trueNode.y = dec.y + yStep;
            }

            // False branch on the RIGHT
            CfgNode falseNode = nodeMap.get("s" + idx + "_false");
            if (falseNode != null) {
                falseNode.x = 160;
                falseNode.y = dec.y + yStep;
            }

            // Merge node BELOW the branches
            CfgNode mergeNode = nodeMap.get("m" + idx);
            if (mergeNode != null) {
                mergeNode.x = 0;
                mergeNode.y = dec.y + (yStep * 2);
            }
        }

        // ── EXIT node at the bottom ──
        CfgNode exit = nodeMap.get("exit");
        if (exit != null) {
            int lastIdx = decisions.size() - 1;
            exit.x = 0;
            exit.y = (lastIdx >= 0) 
                ? (-130 + (lastIdx * yStep * 2) + (yStep * 3)) 
                : 100;
        }
    }
}