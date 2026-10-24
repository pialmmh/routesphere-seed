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

    @Test
    void aUrlWithNoPassword_isPrintedAsItIs() {
        for (String url : new String[]{"redis://10.10.188.2:6379", "redis://ad_sphere@10.10.188.2:6379/0", "jdbc:postgresql://10.10.188.2:5434/routesphere?user=ad&ssl=true",
                "http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere", "10.10.188.2:9092,10.10.188.3:9092"}) {
            assertThat(LogText.maskPassword(url)).isEqualTo(url);
        }
        assertThat(LogText.maskPassword(null)).isNull();
    }
}
