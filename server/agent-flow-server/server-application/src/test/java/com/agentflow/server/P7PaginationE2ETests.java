package com.agentflow.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P7-3#13 游标分页契约：三类列表都必须支持 limit + beforeId（不含游标），
 * 返回 {items,nextCursor}，且管理员/属主过滤不因分页丢边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:af-pagination;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "agentflow.security.enabled=true",
                "agentflow.security.jwt-secret=pagination-secret-at-least-32-chars!!",
                "agentflow.security.rate-limit-per-minute=0",
                "agentflow.security.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "agentflow.nodes.llm.api-key=",
                "agentflow.nodes.image.api-key="})
class P7PaginationE2ETests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void workflowCursorWalksBackwardsWithoutOverlap() {
        User user = registerAndLogin("page_wf");
        for (int i = 1; i <= 5; i++) {
            assertThat(call(HttpMethod.POST, "/api/workflows", dsl("page-w-" + i), user.token()).getBody())
                    .contains("\"code\":\"0\"");
        }

        String first = call(HttpMethod.GET, "/api/workflows?limit=2", null, user.token()).getBody();
        assertThat(first).contains("page-w-5", "page-w-4").doesNotContain("page-w-3");
        long cursor1 = Long.parseLong(number(first, "nextCursor"));

        String second = call(HttpMethod.GET, "/api/workflows?limit=2&beforeId=" + cursor1, null,
                user.token()).getBody();
        assertThat(second).contains("page-w-3", "page-w-2")
                .doesNotContain("page-w-5", "page-w-4");
        long cursor2 = Long.parseLong(number(second, "nextCursor"));

        String last = call(HttpMethod.GET, "/api/workflows?limit=2&beforeId=" + cursor2, null,
                user.token()).getBody();
        assertThat(last).contains("page-w-1").contains("\"nextCursor\":null")
                .doesNotContain("page-w-2", "page-w-3");

        // limit 越界收敛：0 ⇒ 1 条；1000 ⇒ 最多 200 条（此处只有 5 条，不因 clamp 丢数据）
        assertThat(occurrences(call(HttpMethod.GET, "/api/workflows?limit=0", null, user.token()).getBody(),
                "\"name\":\"page-w-")).isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/api/workflows?limit=1000", null, user.token()).getBody())
                .contains("page-w-1", "page-w-5");
    }

    @Test
    void credentialCursorPages() {
        User user = registerAndLogin("page_cred");
        for (int i = 1; i <= 3; i++) {
            assertThat(call(HttpMethod.PUT, "/api/credentials",
                    "{\"provider\":\"prov" + i + "\",\"name\":\"main\",\"secrets\":{\"k\":\"v" + i + "\"}}",
                    user.token()).getBody()).contains("\"code\":\"0\"");
        }

        String first = call(HttpMethod.GET, "/api/credentials?limit=2", null, user.token()).getBody();
        assertThat(first).contains("prov3", "prov2").doesNotContain("prov1");
        long cursor = Long.parseLong(number(first, "nextCursor"));

        String second = call(HttpMethod.GET, "/api/credentials?limit=2&beforeId=" + cursor, null,
                user.token()).getBody();
        assertThat(second).contains("prov1").contains("\"nextCursor\":null").doesNotContain("prov2", "prov3");
    }

    @Test
    void artifactCursorRespectsKindAndOwner() {
        User owner = registerAndLogin("page_art");
        User other = registerAndLogin("page_art_other");
        for (int i = 1; i <= 4; i++) {
            insertArtifact(owner.uid(), "article", "art-" + i);
        }
        insertArtifact(owner.uid(), "note", "note-1");
        insertArtifact(other.uid(), "article", "foreign-art");

        String first = call(HttpMethod.GET, "/api/artifacts?limit=2", null, owner.token()).getBody();
        assertThat(first).contains("note-1", "art-4").doesNotContain("art-3", "foreign-art");
        long cursor = Long.parseLong(number(first, "nextCursor"));

        String second = call(HttpMethod.GET, "/api/artifacts?limit=2&beforeId=" + cursor, null,
                owner.token()).getBody();
        assertThat(second).contains("art-3", "art-2").doesNotContain("note-1", "art-4", "foreign-art");
        long cursor2 = Long.parseLong(number(second, "nextCursor"));

        String third = call(HttpMethod.GET, "/api/artifacts?limit=2&beforeId=" + cursor2, null,
                owner.token()).getBody();
        assertThat(third).contains("art-1").contains("\"nextCursor\":null").doesNotContain("art-2");

        String notes = call(HttpMethod.GET, "/api/artifacts?kind=note&limit=2", null, owner.token()).getBody();
        assertThat(notes).contains("note-1").doesNotContain("art-4");
        assertThat(call(HttpMethod.GET, "/api/artifacts?limit=50", null, other.token()).getBody())
                .contains("foreign-art").doesNotContain("note-1");
    }

    private void insertArtifact(long ownerId, String kind, String title) {
        jdbc.update("INSERT INTO artifact (execution_id, owner_id, kind, title, body, dir) VALUES (0, ?, ?, ?, ?, ?)",
                ownerId, kind, title, "body", "/tmp/af-pagination/" + title);
    }

    private record User(String token, long uid) {
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private User registerAndLogin(String username) {
        String registered = call(HttpMethod.POST, "/api/auth/register",
                "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null).getBody();
        assertThat(registered).contains("\"code\":\"0\"");
        long uid = Long.parseLong(number(registered, "uid"));
        String token = text(call(HttpMethod.POST, "/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"passw0rd-123\"}", null).getBody(), "token");
        return new User(token, uid);
    }

    private static String dsl(String name) {
        return "{\"name\":\"" + name + "\","
                + "\"trigger\":{\"type\":\"manual\"},"
                + "\"nodes\":[{\"id\":\"e\",\"type\":\"core.echo\",\"params\":{\"message\":\"x\"}}],"
                + "\"edges\":[]}";
    }

    private static String text(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        int start = json.indexOf(':', i) + 1;
        assertThat(start).isGreaterThan(0);
        int from = json.charAt(start) == '"' ? start + 1 : start;
        int end = from;
        while (json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(from, end);
    }

    private static String number(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        int start = json.indexOf(':', i) + 1;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return json.substring(start, end);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
