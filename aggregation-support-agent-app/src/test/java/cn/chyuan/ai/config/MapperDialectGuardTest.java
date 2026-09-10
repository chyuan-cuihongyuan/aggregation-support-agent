package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mapper 方言静态断言（工单 0127，三期 PG 双轨；规则同网关 MapperDialectGuardTest）：
 * ①无 databaseId 的公共语句禁用双方言专有模式；②分叉语句各自禁对方模式；
 * ③分叉语句必须成对（同 namespace+id 同时存在 mysql 与 postgresql 版本）。
 */
class MapperDialectGuardTest {

    private static final Pattern[] MYSQL_ONLY = {
            Pattern.compile("ON\\s+DUPLICATE\\s+KEY", Pattern.CASE_INSENSITIVE),
            Pattern.compile("INSERT\\s+IGNORE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DATE_FORMAT\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DATE_ADD\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("FIND_IN_SET\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("GROUP_CONCAT\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("IFNULL\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("=\\s*VALUES\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("LIMIT\\s*#\\{[^}]+}\\s*,"),
            Pattern.compile("`"),
            // PG 侧 user 表为 "user"（引号）；裸 FROM user / INTO user / UPDATE user 在 PG 非法
            Pattern.compile("(FROM|INTO|UPDATE)\\s+user\\b", Pattern.CASE_INSENSITIVE)
    };

    private static final Pattern[] PG_ONLY = {
            Pattern.compile("ON\\s+CONFLICT", Pattern.CASE_INSENSITIVE),
            Pattern.compile("to_char\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("make_interval\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("::halfvec\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("LIMIT\\s+\\d+\\s+OFFSET", Pattern.CASE_INSENSITIVE)
    };

    private static final String MAPPER_DIR = "src/main/resources/mybatis/mapper";

    @Test
    void aggregationMappersRespectDialectRules() throws Exception {
        File dir = new File(MAPPER_DIR);
        assertTrue(dir.isDirectory(), "mapper 目录应存在: " + MAPPER_DIR);

        List<String> violations = new ArrayList<>();
        Map<String, Set<String>> dialectsById = new HashMap<>();

        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        for (File file : files) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(file);
            String namespace = document.getDocumentElement().getAttribute("namespace");

            NodeList statements = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < statements.getLength(); i++) {
                if (!(statements.item(i) instanceof Element element)) {
                    continue;
                }
                String tag = element.getTagName();
                if (!tag.equals("select") && !tag.equals("insert") && !tag.equals("update") && !tag.equals("delete")) {
                    continue;
                }
                String id = element.getAttribute("id");
                String databaseId = element.getAttribute("databaseId");
                String sql = element.getTextContent();

                dialectsById.computeIfAbsent(namespace + "." + id, k -> new HashSet<>()).add(databaseId);

                if (databaseId.isEmpty()) {
                    checkPatterns(file, id, sql, MYSQL_ONLY, "MySQL 专有模式不应出现在公共语句", violations);
                    checkPatterns(file, id, sql, PG_ONLY, "PG 专有模式不应出现在公共语句", violations);
                } else if (databaseId.equals("mysql")) {
                    checkPatterns(file, id, sql, PG_ONLY, "PG 专有模式不应出现在 mysql 分叉语句", violations);
                } else if (databaseId.equals("postgresql")) {
                    checkPatterns(file, id, sql, MYSQL_ONLY, "MySQL 专有模式不应出现在 postgresql 分叉语句", violations);
                }
            }
        }

        dialectsById.forEach((statementId, dialects) -> {
            if (dialects.contains("mysql") && !dialects.contains("postgresql")) {
                violations.add(statementId + "：mysql 分叉缺少 postgresql 配对");
            }
            if (dialects.contains("postgresql") && !dialects.contains("mysql")) {
                violations.add(statementId + "：postgresql 分叉缺少 mysql 配对");
            }
        });

        assertTrue(violations.isEmpty(), "mapper 方言违规:\n" + String.join("\n", violations));
    }

    private void checkPatterns(File file, String id, String sql, Pattern[] patterns,
            String message, List<String> violations) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(sql).find()) {
                violations.add(file.getName() + "#" + id + "：" + message + "（命中 " + pattern + "）");
            }
        }
    }
}
