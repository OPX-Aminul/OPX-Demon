package com.opx.demon.wordlists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

final class BuiltinLists {

    private BuiltinLists() {}

    static final String[] TOP_10_PASSWORDS = {
            "123456", "password", "123456789", "12345678", "12345",
            "qwerty", "1234567890", "1234567", "111111", "123123",
    };

    static final String[] TOP_250_PASSWORDS = {
            "123456", "password", "123456789", "12345678", "12345",
            "qwerty", "1234567890", "1234567", "111111", "123123",
            "abc123", "1234", "password1", "iloveyou", "1q2w3e4r",
            "000000", "qwerty123", "zaq12wsx", "dragon", "sunshine",
            "princess", "letmein", "654321", "monkey", "27653",
            "1qaz2wsx", "123321", "qwertyuiop", "superman", "asdfghjkl",
            "football", "baseball", "welcome", "jordan23", "harley",
            "ranger", "buster", "soccer", "hockey", "killer",
            "george", "andrew", "charlie", "andrea", "jessica",
            "michael", "michelle", "tigger", "pepper", "daniel",
            "hunter", "shadow", "master", "jennifer", "jordan",
            "thomas", "robert", "amanda", "ashley", "matthew",
            "joshua", "nicole", "hannah", "taylor", "william",
            "anthony", "samantha", "victoria", "nathan", "justin",
            "jasmine", "brandon", "austin", "patrick", "richard",
            "jonathan", "sebastian", "admin", "admin123", "root",
            "toor", "guest", "test", "test123", "login",
            "pass", "secret", "computer", "internet", "whatever",
            "freedom", "trustno1", "hello", "hello123", "starwars",
            "pokemon", "batman", "spiderman", "samsung", "google",
            "facebook", "chocolate", "cookie", "flower", "purple",
            "yellow", "silver", "summer", "winter", "spring",
            "autumn", "basketball", "golfer", "fishing", "camaro",
            "corvette", "mustang", "ferrari", "porsche", "mercedes",
            "yamaha", "honda", "diamond", "ginger", "snoopy",
            "mickey", "cheese", "banana", "apple", "orange",
            "lovely", "forever", "angel", "angels", "babygirl",
            "password123", "passw0rd", "Password1", "welcome1", "administrator",
            "666666", "121212", "112233", "789456", "987654321",
            "11111111", "00000000", "555555", "222222", "333333",
            "777777", "888888", "999999", "101010", "131313",
            "159753", "147258369", "123qwe", "1q2w3e", "1q2w3e4r5t",
            "asdfgh", "zxcvbnm", "qazwsx", "qweasdzxc", "qweqwe",
            "q1w2e3r4", "azerty", "qwertz", "112358", "1122334455",
            "abcd1234", "abc12345", "a123456", "1234qwer", "asd123",
            "123abc", "qwe123", "159357", "753951", "741852",
            "love", "lovers", "loveme", "iloveu", "loveyou",
            "friend", "friends", "family", "mother", "father",
            "chelsea", "arsenal", "liverpool", "barcelona", "juventus",
            "united", "rangers", "cowboys", "yankees", "lakers",
            "boston", "chicago", "newyork", "london", "paris",
            "berlin", "moscow", "canada", "mexico", "brasil",
            "phoenix", "eagle", "eagles", "falcon", "raptor",
            "tiger", "tigers", "lion", "wolf", "bear",
            "panther", "cobra", "viper", "python", "java",
            "linux", "ubuntu", "windows", "android", "iphone",
            "samsung1", "nokia", "motorola", "netgear", "linksys",
            "dlink", "tplink", "cisco", "oracle", "mysql",
            "server", "system", "manager", "service", "backup",
            "default", "changeme", "letmein1", "opensesame", "matrix",
    };

    static final String[] TOP_20_PINS = {
            "1234", "1111", "0000", "1212", "7777",
            "1004", "2000", "4444", "2222", "6969",
            "9999", "3333", "5555", "6666", "1122",
            "1313", "8888", "4321", "2001", "1010",
    };

    static List<String> top250Pins() {
        LinkedHashSet<String> out = new LinkedHashSet<>(Arrays.asList(TOP_20_PINS));

        for (int a = 0; a <= 9 && out.size() < 250; a++) {
            for (int b = 0; b <= 9 && out.size() < 250; b++) {
                out.add("" + a + b + a + b);
                out.add("" + a + b + b + a);
            }
        }
        for (int a = 0; a <= 9 && out.size() < 250; a++) {
            StringBuilder up = new StringBuilder();
            StringBuilder down = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                up.append((a + i) % 10);
                down.append(((a - i) % 10 + 10) % 10);
            }
            out.add(up.toString());
            out.add(down.toString());
        }
        for (int m = 1; m <= 12 && out.size() < 250; m++) {
            for (int d = 1; d <= 31 && out.size() < 250; d++) {
                out.add(String.format(java.util.Locale.US, "%02d%02d", m, d));
            }
        }
        for (int y = 2026; y >= 1950 && out.size() < 250; y--) out.add(String.valueOf(y));

        List<String> list = new ArrayList<>(out);
        return list.size() > 250 ? list.subList(0, 250) : list;
    }

    static List<String> allFourDigitPins() {
        LinkedHashSet<String> out = new LinkedHashSet<>(top250Pins());
        for (int i = 0; i < 10000; i++) out.add(String.format(java.util.Locale.US, "%04d", i));
        return new ArrayList<>(out);
    }

    static final String[] WIFI_DEFAULTS = {
            "password", "12345678", "123456789", "1234567890", "0123456789",
            "administrator", "internet", "wireless", "birthday", "computer",
            "qwertyuiop", "asdfghjkl", "iloveyou", "sunshine", "princess",
            "football", "baseball", "starwars", "superman", "welcome1",
            "changeme", "letmein1", "password1", "password123", "abcd1234",
            "11111111", "00000000", "88888888", "12341234", "qwerty123",
            "wifipassword", "mypassword", "homewifi", "guestwifi", "freewifi",
    };

    static final String[] COMMON_USERNAMES = {
            "root", "admin", "administrator", "user", "test",
            "guest", "ubuntu", "pi", "oracle", "postgres",
            "mysql", "ftp", "www", "www-data", "nginx",
            "apache", "tomcat", "jenkins", "git", "docker",
            "backup", "operator", "support", "service", "manager",
            "info", "sales", "demo", "dev", "deploy",
            "sysadmin", "webadmin", "adm", "toor", "daemon",
    };

    static List<String> keyboardWalks() {
        return Arrays.asList(
                "qwerty", "qwertyui", "qwertyuiop", "asdfgh", "asdfghjk",
                "asdfghjkl", "zxcvbn", "zxcvbnm", "qazwsx", "qazwsxedc",
                "1qaz2wsx", "1qaz2wsx3edc", "zaq12wsx", "xsw23edc", "1q2w3e",
                "1q2w3e4r", "1q2w3e4r5t", "q1w2e3r4", "q1w2e3r4t5", "qweasd",
                "qweasdzxc", "qwerasdf", "poiuyt", "lkjhgf", "mnbvcxz",
                "0987654321", "9876543210", "azerty", "azertyuiop", "qwertz"
        );
    }
}
