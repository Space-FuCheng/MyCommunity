package com.nowcoder.community;

import java.io.IOException;

public class WkTests {
    public static void main(String[] args) throws IOException {
        String cmd = "C:/BaiduYunDownload/niuke/work/wkhtmltopdf/bin/wkhtmltoimage " +
                "--quality 75 www.nowcoder.com C:/BaiduYunDownload/niuke/work/data/wk-images/3.png";
        Runtime.getRuntime().exec(cmd);
        System.out.println("ok");
    }
}
