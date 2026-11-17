package analyser.controller;

import analyser.parser.ConditionExtractor;
import analyser.parser.ConditionExtractor.Decision;
import analyser.cfg.CfgBuilder;
import analyser.cfg.CfgBuilder.Cfg;
import analyser.cfg.CfgBuilder.CfgNode;
import analyser.cfg.CfgBuilder.CfgEdge;
import analyser.mcdc.McdcEngine;
import analyser.mcdc.McdcEngine.McdcResult;
import analyser.mcdc.McdcEngine.TestCase;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.*;

@CrossOrigin(origins = "*")
@RestController
public class AnalyserController {

    // ── Analyse endpoint ──────────────────────────────────────────────────────

    @PostMapping("/api/analyse")
    public ResponseEntity<?> analyse(@RequestBody Map<String, String> body) {
        String sourceCode = body.get("sourceCode");

        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No source code provided"));
        }

        if (!sourceCode.contains("class ")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Code must be a complete Java class"));
        }

        long lineCount = sourceCode.lines().count();
        if (lineCount > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "Code exceeds 100 line limit. Please paste a shorter snippet."));
        }

        try {
            // Step 1 — Extract decisions
            ConditionExtractor extractor = new ConditionExtractor();
            List<Decision> decisions = extractor.extract(sourceCode);

            // Step 2 — Build CFG
            CfgBuilder builder = new CfgBuilder();
            Cfg cfg = builder.build(decisions);

            // Step 3 — Run MC/DC on each decision
            McdcEngine engine = new McdcEngine();
            List<Map<String, Object>> allTestCases = new ArrayList<>();
            int totalConditions = 0;
            int coveredConditions = 0;

            for (int i = 0; i < decisions.size(); i++) {
                Decision d = decisions.get(i);
                String nodeId = "d" + i;
                McdcResult result = engine.analyse(d, nodeId);

                totalConditions += result.total;
                coveredConditions += result.covered;

                for (TestCase tc : result.testCases) {
                    Map<String, Object> tcMap = new HashMap<>();
                    tcMap.put("id",               tc.id);
                    tcMap.put("inputs",            tc.inputs);
                    tcMap.put("output",            tc.output);
                    tcMap.put("conditionTested",   tc.conditionTested);
                    tcMap.put("nodeId",            tc.nodeId);
                    tcMap.put("explanation",       tc.explanation);
                    allTestCases.add(tcMap);
                }
            }

            // Step 4 — Build CFG JSON
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (CfgNode n : cfg.nodes) {
                Map<String, Object> node = new HashMap<>();
                node.put("id",    n.id);
                node.put("type",  n.type);
                node.put("label", n.label);
                node.put("x",     n.x);
                node.put("y",     n.y);
                nodes.add(node);
            }

            List<Map<String, Object>> edges = new ArrayList<>();
            for (CfgEdge e : cfg.edges) {
                Map<String, Object> edge = new HashMap<>();
                edge.put("from",  e.from);
                edge.put("to",    e.to);
                edge.put("label", e.label);
                edges.add(edge);
            }

            Map<String, Object> cfgMap = new HashMap<>();
            cfgMap.put("nodes", nodes);
            cfgMap.put("edges", edges);

            // Step 5 — Build condition labels from first decision
            List<String> conditionLabels = new ArrayList<>();
            if (!decisions.isEmpty()) {
                for (ConditionExtractor.Condition c : decisions.get(0).conditions) {
                    conditionLabels.add(c.expression);
                }
            }

            // Step 6 — Coverage
            double percentage = totalConditions == 0 ? 0 :
                (coveredConditions * 100.0 / totalConditions);

            // Step 7 — Build full response
            Map<String, Object> response = new HashMap<>();
            response.put("methodName",      extractMethodName(sourceCode));
            response.put("cfg",             cfgMap);
            response.put("testCases",       allTestCases);
            response.put("conditionLabels", conditionLabels);
            response.put("coverage", Map.of(
                "total",      totalConditions,
                "covered",    coveredConditions,
                "percentage", percentage
            ));

            return ResponseEntity.ok(response);

       } catch (Exception e) {
    String msg = e.getMessage();
    String friendly;
    if (msg != null && msg.contains("Parse error")) {
        // Extract just the line number
        String lineInfo = msg.contains("line") ? 
            msg.substring(msg.indexOf("(line"), msg.indexOf("(line") + 20).split("\\)")[0] + ")" : "";
        friendly = "Syntax error in your Java code " + lineInfo + ". Make sure the code is complete and valid.";
    } else {
        friendly = "Could not analyse code. Make sure it is valid Java with at least one if/while/for statement.";
    }
    return ResponseEntity.badRequest().body(Map.of("error", friendly));
}
    }

    // ── Export endpoint ───────────────────────────────────────────────────────

    @PostMapping("/api/export")
public ResponseEntity<String> export(
        @RequestParam String format,
        @RequestBody Map<String, Object> data) {

    try {
        if (format.equals("html")) {
            String html = buildHtmlReport(data);
            return ResponseEntity.ok()
                .header("Content-Type", "text/html")
                .header("Content-Disposition", "attachment; filename=mcdc-report.html")
                .body(html);
        }
        return ResponseEntity.badRequest().body("Unknown format: " + format);

    } catch (Exception e) {
        return ResponseEntity.badRequest().body("Export failed: " + e.getMessage());
    }
}

    // ── HTML report builder ───────────────────────────────────────────────────

    private String buildHtmlReport(Map<String, Object> data) {
        String methodName = (String) data.getOrDefault("methodName", "unknown");

        List<Map<String, Object>> testCases =
            (List<Map<String, Object>>) data.get("testCases");
        Map<String, Object> coverage =
            (Map<String, Object>) data.get("coverage");
        List<String> labels =
            (List<String>) data.get("conditionLabels");

        // Build table header columns
        StringBuilder headers = new StringBuilder();
        headers.append("<th>TC</th>");
        if (labels != null) {
            for (String l : labels) {
                headers.append("<th>").append(l).append("</th>");
            }
        }
        headers.append("<th>Result</th><th>Explanation</th>");

        // Build table rows
        StringBuilder rows = new StringBuilder();
        if (testCases != null) {
            for (Map<String, Object> tc : testCases) {
                List<Boolean> inputs = (List<Boolean>) tc.get("inputs");
                boolean output = (Boolean) tc.get("output");
                String id = (String) tc.get("id");
                String explanation = (String) tc.getOrDefault("explanation", "");
                String condTested  = (String) tc.getOrDefault("conditionTested", "");

                rows.append("<tr>");
                rows.append("<td class='tc-id'>").append(id).append("</td>");
                if (inputs != null) {
                    for (Boolean v : inputs) {
                        rows.append("<td class='").append(v ? "val-t" : "val-f").append("'>")
                            .append(v ? "T" : "F").append("</td>");
                    }
                }
                rows.append("<td class='").append(output ? "val-t" : "val-f").append("'>")
                    .append(output ? "true" : "false").append("</td>");
                rows.append("<td class='exp'>")
                    .append(explanation != null ? explanation : "").append("</td>");
                rows.append("</tr>");
            }
        }

        int pct     = coverage != null ? ((Number) coverage.getOrDefault("percentage", 0)).intValue() : 0;
        int total   = coverage != null ? ((Number) coverage.getOrDefault("total",      0)).intValue() : 0;
        int covered = coverage != null ? ((Number) coverage.getOrDefault("covered",    0)).intValue() : 0;
        int tcCount = testCases != null ? testCases.size() : 0;

        return "<!DOCTYPE html>\n" +
            "<html lang='en'>\n" +
            "<head>\n" +
            "  <meta charset='UTF-8'/>\n" +
            "  <title>MC/DC Report — " + methodName + "</title>\n" +
            "  <style>\n" +
            "    body { font-family: 'Segoe UI', sans-serif; background: #0d0d0f; color: #f0efec; padding: 40px; }\n" +
            "    h1 { font-size: 1.6rem; margin-bottom: 4px; }\n" +
            "    .sub { color: #888; font-size: 14px; margin-bottom: 32px; }\n" +
            "    table { width: 100%; border-collapse: collapse; font-size: 13px; margin-bottom: 32px; }\n" +
            "    th { text-align: left; padding: 10px; background: #17171b; border-bottom: 1px solid #333;\n" +
            "         color: #888; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; }\n" +
            "    td { padding: 10px; border-bottom: 1px solid #1a1a1e; }\n" +
            "    .tc-id { color: #6c63ff; font-weight: 700; font-family: monospace; }\n" +
            "    .val-t { color: #00d4a8; font-weight: 700; }\n" +
            "    .val-f { color: #ff5f5f; font-weight: 700; }\n" +
            "    .exp { color: #888; font-size: 12px; line-height: 1.5; }\n" +
            "    tr:hover td { background: rgba(255,255,255,.03); }\n" +
            "    .cov-box { background: #17171b; border: 1px solid #222; border-radius: 10px; padding: 24px; max-width: 400px; }\n" +
            "    .cov-pct { font-size: 2.5rem; font-weight: 700; color: #00d4a8; }\n" +
            "    .cov-label { color: #888; font-size: 13px; margin-top: 4px; }\n" +
            "    .bar-track { background: #222; border-radius: 4px; height: 8px; margin: 16px 0; }\n" +
            "    .bar-fill { background: #00d4a8; height: 100%; border-radius: 4px; }\n" +
            "    .meta { display: flex; gap: 24px; margin-top: 16px; }\n" +
            "    .meta-item { text-align: center; }\n" +
            "    .meta-val { font-size: 1.4rem; font-weight: 700; }\n" +
            "    .meta-lbl { font-size: 11px; color: #888; margin-top: 2px; }\n" +
            "    .footer { margin-top: 48px; font-size: 11px; color: #444; border-top: 1px solid #222; padding-top: 16px; }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <h1>MC/DC Analysis Report</h1>\n" +
            "  <div class='sub'>Method: " + methodName + " &nbsp;·&nbsp; " + tcCount + " test cases &nbsp;·&nbsp; Generated " + new java.util.Date() + "</div>\n" +
            "  <table>\n" +
            "    <thead><tr>" + headers + "</tr></thead>\n" +
            "    <tbody>" + rows + "</tbody>\n" +
            "  </table>\n" +
            "  <div class='cov-box'>\n" +
            "    <div class='cov-pct'>" + pct + "%</div>\n" +
            "    <div class='cov-label'>MC/DC Coverage</div>\n" +
            "    <div class='bar-track'><div class='bar-fill' style='width:" + pct + "%'></div></div>\n" +
            "    <div class='meta'>\n" +
            "      <div class='meta-item'><div class='meta-val'>" + covered + "</div><div class='meta-lbl'>conditions covered</div></div>\n" +
            "      <div class='meta-item'><div class='meta-val'>" + total + "</div><div class='meta-lbl'>total conditions</div></div>\n" +
            "    </div>\n" +
            "  </div>\n" +
            "  <div class='footer'>Generated by CFG-MCDC Analyser Tool</div>\n" +
            "</body>\n" +
            "</html>";
    }

    // ── Helper — extract method name ──────────────────────────────────────────

    private String extractMethodName(String source) {
        for (String line : source.split("\n")) {
            line = line.trim();
            if (line.contains("(") && (line.contains("public") ||
                line.contains("private") || line.contains("protected"))) {
                int start = line.lastIndexOf(' ', line.indexOf('(')) + 1;
                int end   = line.indexOf('(');
                if (start > 0 && end > start) {
                    return line.substring(start, end) + "()";
                }
            }
        }
        return "unknown()";
    }
}
