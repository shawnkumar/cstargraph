package org.apache.cassandra.stress;

import com.google.common.io.ByteStreams;

import org.apache.cassandra.stress.settings.StressSettings;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.omg.SendingContext.RunTime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class StressGraph
{

    private StressSettings stressSettings;
    private enum readingMode {START, METRICS, AGGREGATES, NEXTITERATION, END};
    private String[] stressArguments;

    public StressGraph(StressSettings stressSetttings, String[] stressArguments)
    {
        this.stressSettings = stressSetttings;
        this.stressArguments = stressArguments;
    }

    public void generateGraph()
    {
        File htmlFile = new File(stressSettings.graph.file);
        JSONObject stats;
        if (htmlFile.isFile())
        {
            try
            {
                String html = new String(Files.readAllBytes(Paths.get(htmlFile.toURI())), StandardCharsets.UTF_8);
                stats = parseExistingStats(html);
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new RuntimeException("Couldn't load existing stats html.");
            }
            stats = this.createJSONStats(stats);
        }
        else
        {
            stats = this.createJSONStats(null);
        }

        try
        {
            PrintWriter out = new PrintWriter(htmlFile);
            String statsBlock = "/* stats start */\nstats = " + stats.toJSONString() + ";\n/* stats end */\n";
            String html = getGraphHTML().replaceFirst("/\\* stats start \\*/\n\n/\\* stats end \\*/\n", statsBlock);
            out.write(html);
            out.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private JSONObject parseExistingStats(String html)
    {
        JSONObject stats;

        Pattern pattern = Pattern.compile("(?s).*/\\* stats start \\*/\\nstats = (.*);\\n/\\* stats end \\*/.*");
        Matcher matcher = pattern.matcher(html);
        matcher.matches();
        stats = (JSONObject) JSONValue.parse(matcher.group(1));

        return stats;
    }

    private String getGraphHTML()
    {
        InputStream graphHTMLRes = StressGraph.class.getClassLoader().getResourceAsStream("org/apache/cassandra/stress/graph/graph.html");
        String graphHTML;
        try
        {
            graphHTML = new String(ByteStreams.toByteArray(graphHTMLRes));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        return graphHTML;
    }

    /** Parse log and append to stats array */
    private JSONArray parseLogStats(InputStream log, JSONArray stats) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(log));
        JSONObject json = new JSONObject();
        JSONArray intervals = new JSONArray();
        boolean runningMultipleThreadCounts = false;
        String currentThreadCount = null;
        Pattern threadCountMessage = Pattern.compile("Running ([A-Z]+) with ([0-9]+) threads .*");
        readingMode mode = readingMode.START;

        try
        {
            String line;
            while (!mode.equals(readingMode.END))
            {
                //Read the next line, break if null:
                line = reader.readLine();
                if (line == null)
                {
                    break;
                }

                //Detect if we are running multiple thread counts:
                if (line.startsWith("Thread count was not specified"))
                {
                    runningMultipleThreadCounts = true;
                }

                //Detect thread count:
                Matcher tc = threadCountMessage.matcher(line);
                if (tc.matches())
                {
                    if (runningMultipleThreadCounts)
                    {
                        currentThreadCount = tc.group(2);
                    }
                }

                //Detect mode changes:
                if (line.equals(StressMetrics.HEAD))
                {
                    mode = readingMode.METRICS;
                    continue;
                }
                else if (line.equals("Results:"))
                {
                    mode = readingMode.AGGREGATES;
                    continue;
                }
                else if (mode == readingMode.AGGREGATES && line.equals(""))
                {
                    mode = readingMode.NEXTITERATION;
                }
                else if (line.equals("END") || line.equals("FAILURE"))
                {
                    mode = readingMode.END;
                    break;
                }

                //Process lines:
                if (mode == readingMode.METRICS)
                {
                    JSONArray metrics = new JSONArray();
                    String[] parts = line.split(",");
                    if (parts.length != StressMetrics.HEADMETRICS.length){
                        continue;
                    }
                    for (String m : parts)
                    {
                        try
                        {
                            metrics.add(new BigDecimal(m.trim()));
                        }
                        catch (NumberFormatException e)
                        {
                            metrics.add(null);
                        }
                    }
                    intervals.add(metrics);
                }
                else if (mode == readingMode.AGGREGATES)
                {
                    String[] parts = line.split(":",2);
                    if (parts.length != 2)
                    {
                        continue;
                    }
                    json.put(parts[0].trim(), parts[1].trim());
                }
                else if (mode == readingMode.NEXTITERATION)
                {
                    //Wrap up the results of this test and append to the array.
                    json.put("metrics", Arrays.asList(StressMetrics.HEADMETRICS));
                    json.put("test", stressSettings.graph.operation);
                    if (currentThreadCount == null)
                        json.put("revision", stressSettings.graph.revision);
                    else
                        json.put("revision", String.format("%s - %s threads", stressSettings.graph.revision, currentThreadCount));
                    json.put("command", StringUtils.join(stressArguments, " "));
                    json.put("intervals", intervals);
                    stats.add(json);
                    //Start fresh for next iteration:
                    json = new JSONObject();
                    intervals = new JSONArray();
                    mode = readingMode.START;
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        stats.add(json);
        return stats;
    }

    private JSONObject createJSONStats(JSONObject json)
    {
        JSONArray stats;
        JSONObject parsedMetrics;
        if(json == null){
            json = new JSONObject();
            stats = new JSONArray();
        } else {
            stats = (JSONArray) json.get("stats");
        }

        try
        {
            stats = parseLogStats(new FileInputStream(stressSettings.graph.temporaryLogFile), stats);
        }
        catch (FileNotFoundException e)
        {
            throw new RuntimeException(e);
        }

        json.put("title", stressSettings.graph.title);
        json.put("stats", stats);
        return json;
    }
}
