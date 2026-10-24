package com.telcobright.seed.logtext;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-M item 2 · a password inside a url is never printed: the userinfo's password and every parameter whose name says password, passwd,
 * pwd or secret (in the query or after a ';', case-blind) are written {@value LogText#MASK}; the rest of the url as it is. The secrets here
 * are generated.
 */
class MaskPasswordTest {

    private final String s = "pw-" + UUID.randomUUID(), t = "pw-" + UUID.randomUUID();

    @Test
    void redis_aPasswordOnly_orAUserAndAPassword() {
        assertThat(LogText.maskPassword("redis://:" + s + "@10.10.188.2:6379/0")).isEqualTo("redis://:***@10.10.188.2:6379/0");
        assertThat(LogText.maskPassword("rediss://ad_sphere:" + s + "@10.10.188.2:6380")).isEqualTo("rediss://ad_sphere:***@10.10.188.2:6380");
    }

    @Test
    void jdbc_aPasswordInTheQuery_firstOrAmongOthers() {
        assertThat(LogText.maskPassword("jdbc:postgresql://10.10.188.2:5434/routesphere?password=" + s))
            .isEqualTo("jdbc:postgresql://10.10.188.2:5434/routesphere?password=***");
        assertThat(LogText.maskPassword("jdbc:postgresql://10.10.188.2:5434/routesphere?user=ad&password=" + s + "&ssl=true"))
            .isEqualTo("jdbc:postgresql://10.10.188.2:5434/routesphere?user=ad&password=***&ssl=true");
    }

    @Test
    void jdbc_aPasswordAfterASemicolon() {
        assertThat(LogText.maskPassword("jdbc:sqlserver://10.10.1.1:1433;user=ad;password=" + s + ";encrypt=true"))
            .isEqualTo("jdbc:sqlserver://10.10.1.1:1433;user=ad;password=***;encrypt=true");
        assertThat(LogText.maskPassword("jdbc:h2:mem:lab;MODE=MySQL;PASSWORD=" + s)).isEqualTo("jdbc:h2:mem:lab;MODE=MySQL;PASSWORD=***");
    }

    @Test
    void mysql_aUserinfoPassword_andOneInTheQuery() {
        assertThat(LogText.maskPassword("jdbc:mysql://ad:" + s + "@10.10.1.1:3306/routesphere?useSSL=false&Password=" + t))
            .isEqualTo("jdbc:mysql://ad:***@10.10.1.1:3306/routesphere?useSSL=false&Password=***");
    }

    @Test
    void everyNameThatSaysAPassword_caseBlind() {
        for (String name : new String[]{"pwd", "PWD", "passwd", "secret", "SECRET", "client_secret", "sslpassword", "keyStorePassword"}) {
            assertThat(LogText.maskPassword("https://portal.test/api?x=1&" + name + "=" + s + "#top")).as(name)
                .isEqualTo("https://portal.test/api?x=1&" + name + "=***#top");
        }
    }

    // ── P1-N · three forms 778531f printed raw (an over-mask beats a leak) ──

    @Test
    void aUserinfoWithoutAColon_isMaskedWhole_LettucesPassword_orABareToken() {
        assertThat(LogText.maskPassword("redis://" + s + "@10.10.188.2:6379/0")).isEqualTo("redis://***@10.10.188.2:6379/0");
        assertThat(LogText.maskPassword("https://" + s + "@portal.test/x")).isEqualTo("https://***@portal.test/x");
        assertThat(LogText.maskPassword("redis://ad_sphere@10.10.188.2:6379/0")).as("a user name alone is lost from the line (P1-M printed it): accepted")
            .isEqualTo("redis://***@10.10.188.2:6379/0");
    }

    @Test
    void aUserinfoPassword_carryingASemicolonOrASlash_isMaskedToTheLastAt() {
        assertThat(LogText.maskPassword("jdbc:mysql://u:pa;" + s + "@10.10.1.1:3306/db")).isEqualTo("jdbc:mysql://u:***@10.10.1.1:3306/db");
        assertThat(LogText.maskPassword("jdbc:mysql://u:pa/" + s + "@10.10.1.1:3306/db")).isEqualTo("jdbc:mysql://u:***@10.10.1.1:3306/db");
    }

    @Test
    void aKeyValueBlockThatNamesAPassword_isMaskedLikeAParameter() {
        assertThat(LogText.maskPassword("jdbc:mysql://address=(host=10.10.1.1)(port=3306)(user=u)(password=" + s + ")/db"))
            .isEqualTo("jdbc:mysql://address=(host=10.10.1.1)(port=3306)(user=u)(password=***)/db");
        assertThat(LogText.maskPassword("jdbc:mysql://address=(host=h)(PWD=" + s + ")(sslpassword=" + t + ")/db"))
            .isEqualTo("jdbc:mysql://address=(host=h)(PWD=***)(sslpassword=***)/db");
    }

    @Test
    void theseStayAsTheyWere() {
        assertThat(LogText.maskPassword("jdbc:mysql://h:3306/db?user=a@b.com&password=" + s)).as("an '@' in the query is not a userinfo")
            .isEqualTo("jdbc:mysql://h:3306/db?user=a@b.com&password=***");
        assertThat(LogText.maskPassword("jdbc:mysql://[::1]:3306/db")).isEqualTo("jdbc:mysql://[::1]:3306/db");
        assertThat(LogText.maskPassword("jdbc:mysql://u:" + s + "@[::1]:3306/db")).isEqualTo("jdbc:mysql://u:***@[::1]:3306/db");
        assertThat(LogText.maskPassword("redis://:p@" + s + "@10.10.188.2/0")).isEqualTo("redis://:***@10.10.188.2/0");
        assertThat(LogText.maskPassword("jdbc:mariadb://u:p%40" + s + "@h/db")).isEqualTo("jdbc:mariadb://u:***@h/db");
        assertThat(LogText.maskPassword("redis://@10.10.188.2:6379")).as("an empty userinfo has nothing to hide").isEqualTo("redis://@10.10.188.2:6379");
    }

    @Test
    void anAtInAPath_overMasks_accepted() {
        assertThat(LogText.maskPassword("https://portal.test/users/a@b/x")).isEqualTo("https://***@b/x");
    }

    @Test
    void aUrlWithNoPassword_isPrintedAsItIs() {
        for (String url : new String[]{"redis://10.10.188.2:6379", "jdbc:postgresql://10.10.188.2:5434/routesphere?user=ad&ssl=true",
                "http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere", "10.10.188.2:9092,10.10.188.3:9092"}) {
            assertThat(LogText.maskPassword(url)).isEqualTo(url);
        }
        assertThat(LogText.maskPassword(null)).isNull();
    }
}
