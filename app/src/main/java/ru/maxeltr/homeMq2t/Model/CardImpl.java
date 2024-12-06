/*
 * The MIT License
 *
 * Copyright 2023 Maxim Eltratov <<Maxim.Eltratov@ya.ru>>.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ru.maxeltr.homeMq2t.Model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

/**
 *
 * @author Maxim Eltratov <<Maxim.Eltratov@ya.ru>>
 */
public class CardImpl implements Card {

    @Autowired
    private Environment env;

    @Value("classpath:card.html")
    private Resource res;

    private String name = "";

    private String text = "";

    private final String svg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" class="bi bi-display" viewBox="0 0 16 16">
            <path d="M0 4s0-2 2-2h12s2 0 2 2v6s0 2-2 2h-4c0 .667.083 1.167.25 1.5H11a.5.5 0 0 1 0 1H5a.5.5 0 0 1 0-1h.75c.167-.333.25-.833.25-1.5H2s-2 0-2-2V4zm1.398-.855a.758.758 0 0 0-.254.302A1.46 1.46 0 0 0 1 4.01V10c0 .325.078.502.145.602.07.105.17.188.302.254a1.464 1.464 0 0 0 .538.143L2.01 11H14c.325 0 .502-.078.602-.145a.758.758 0 0 0 .254-.302 1.464 1.464 0 0 0 .143-.538L15 9.99V4c0-.325-.078-.502-.145-.602a.757.757 0 0 0-.302-.254A1.46 1.46 0 0 0 13.99 3H2c-.325 0-.502.078-.602.145z"/>
        </svg>""";

    public CardImpl(String name) {
        this.name = name;
    }

    public CardImpl(String name, String text) {
        this.name = name;
        this.text = text;
    }

    public String getName() {
        return this.name;
    }

    public String getText() {
        return this.text;
    }

    public String getHtml() {
        return this.getHtmlFromFile();
//        StringBuilder builder = new StringBuilder();
//        builder.append("<div class=\"card text-center text-white bg-secondary shadow-sm \" id=\"");
//        builder.append(this.getName()).append("\">");
//        builder.append("<div class=\"align-items-center\" id=\"");
//        builder.append(this.getName()).append("-payload\">");
//        builder.append(this.svg);
//        builder.append("</div><div class=\"card-body\"><h5 class=\"card-title\">");
//        builder.append(this.getName());
//        builder.append("</h5><p class=\"card-text\">");
//        builder.append(this.getText());
//        builder.append("</p><div class=\"btn-group\">");
//        builder.append("<button type=\"button\" class=\"btn btn-sm btn-outline-light\" id=\"sendCommand\" value=\"1\">Get</button>");
//        builder.append("<a href=\"\" download=\"image.jpg\" role=\"button\" class=\"btn btn-sm btn-outline-light disabled\" id=\"");
//        builder.append(this.getName()).append("-save\">Save</a></div><small class=\"text-dark\"> </small></div>");
//        builder.append("<div class=\"card-footer text-dark\" id=\"");
//        builder.append(this.getName()).append("-timestamp\"> </div></div>");
//
//        return builder.toString();
    }

    public String getHtmlFromFile() {
        List<String> lines = new ArrayList<>();;
        try {
            lines = Files.readAllLines(Paths.get(res.getURI()),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Logger.getLogger(CardImpl.class.getName()).log(Level.SEVERE, null, ex);
        }

        for (String line : lines) {

            System.out.println(line);

        }
        
        return "getHtmlFromFile";
    }
}
