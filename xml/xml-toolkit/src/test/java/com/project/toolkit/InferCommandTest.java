package com.project.toolkit;

import com.project.toolkit.infer.InferCommand;
import com.project.toolkit.infer.InferOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InferCommandTest {

    @Test
    void detectsOptionalAndRepeatingAndDatatypes(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("sample1.xml"), """
                <Order>
                    <Qty>1001</Qty>
                    <Note>first order</Note>
                    <Item>A</Item>
                    <Item>B</Item>
                    <Item>C</Item>
                </Order>
                """);
        Files.writeString(tempDir.resolve("sample2.xml"), """
                <Order>
                    <Qty>1002</Qty>
                    <Item>A</Item>
                </Order>
                """);

        InferOptions opts = new InferOptions();
        opts.xmlInput = tempDir;
        opts.outXsd = tempDir.resolve("inferred.xsd");

        InferCommand.run(opts);

        String xsd = Files.readString(opts.outXsd);
        assertTrue(Files.exists(opts.outXsd));

        // Qty appeared in every file as an integer -> required, xs:integer
        assertTrue(xsd.contains("name=\"Qty\" type=\"xs:integer\""), "Qty should be inferred as a required integer:\n" + xsd);

        // Note appeared only in sample1 -> optional
        assertTrue(xsd.contains("name=\"Note\" type=\"xs:string\" minOccurs=\"0\""), "Note should be optional:\n" + xsd);

        // Item repeated 3 times in sample1 -> maxOccurs unbounded
        assertTrue(xsd.contains("name=\"Item\"") && xsd.contains("maxOccurs=\"unbounded\""), "Item should repeat:\n" + xsd);

        Path report = tempDir.resolve("inferred_review.md");
        assertTrue(Files.exists(report), "review report should be written next to the XSD");
        String reportText = Files.readString(report);
        assertTrue(reportText.contains("Note"), "review report should mention the optional Note field:\n" + reportText);
    }

    @Test
    void keepsIdRefNbFieldsAsStringToPreserveLeadingZeros(@TempDir Path tempDir) throws IOException {
        // Every sample's OrdId "looks like" an integer, but it has a leading zero -- coercing it
        // to xs:integer would be lossy, so the Id/Ref/Nb tag-name rule keeps it as xs:string.
        Files.writeString(tempDir.resolve("sample1.xml"), "<Order><OrdId>00042</OrdId></Order>");
        Files.writeString(tempDir.resolve("sample2.xml"), "<Order><OrdId>00099</OrdId></Order>");

        InferOptions opts = new InferOptions();
        opts.xmlInput = tempDir;
        opts.outXsd = tempDir.resolve("inferred.xsd");

        InferCommand.run(opts);

        String xsd = Files.readString(opts.outXsd);
        assertTrue(xsd.contains("name=\"OrdId\" type=\"xs:string\""), "OrdId should stay xs:string, not be coerced to a number:\n" + xsd);
    }

    @Test
    void infersTrueOrderEvenWhenFirstSampleIsMissingAnEarlyOptionalElement(@TempDir Path tempDir) throws IOException {
        // Alphabetically first, so scanned first: "A" is optional and happens to be missing
        // here, so a naive first-seen-order writer would place "B" before "A" in the XSD.
        Files.writeString(tempDir.resolve("sample1.xml"), "<Root><B>b1</B><C>c1</C></Root>");
        Files.writeString(tempDir.resolve("sample2.xml"), "<Root><A>a2</A><B>b2</B><C>c2</C></Root>");
        Files.writeString(tempDir.resolve("sample3.xml"), "<Root><B>b3</B><C>c3</C></Root>");

        InferOptions opts = new InferOptions();
        opts.xmlInput = tempDir;
        opts.outXsd = tempDir.resolve("inferred.xsd");

        InferCommand.run(opts);

        String xsd = Files.readString(opts.outXsd);
        int posA = xsd.indexOf("name=\"A\"");
        int posB = xsd.indexOf("name=\"B\"");
        int posC = xsd.indexOf("name=\"C\"");
        assertTrue(posA >= 0 && posA < posB && posB < posC,
                "declared order should be A, B, C (the true order seen in sample2), not scan-discovery order:\n" + xsd);
    }

    @Test
    void mergesStructurallyIdenticalComplexTypes(@TempDir Path tempDir) throws IOException {
        // WrapA and WrapB both wrap a single required integer "Val" -- structurally identical,
        // so they should end up sharing one named complexType instead of getting one each.
        Files.writeString(tempDir.resolve("sample1.xml"), "<Root><WrapA><Val>1</Val></WrapA><WrapB><Val>2</Val></WrapB></Root>");

        InferOptions opts = new InferOptions();
        opts.xmlInput = tempDir;
        opts.outXsd = tempDir.resolve("inferred.xsd");

        InferCommand.run(opts);

        String xsd = Files.readString(opts.outXsd);
        assertFalse(xsd.contains("WrapBType"), "WrapB should not get its own type:\n" + xsd);
        assertTrue(xsd.contains("name=\"WrapA\" type=\"WrapAType\"") && xsd.contains("name=\"WrapB\" type=\"WrapAType\""),
                "WrapA and WrapB should both reference the same shared complexType:\n" + xsd);
    }

    @Test
    void warnsWhenOnlyOneSampleGiven(@TempDir Path tempDir) throws IOException {
        Path xml = tempDir.resolve("only.xml");
        Files.writeString(xml, "<Root><A>1</A></Root>");

        InferOptions opts = new InferOptions();
        opts.xmlInput = xml;
        opts.outXsd = tempDir.resolve("only.xsd");

        InferCommand.run(opts);

        String report = Files.readString(tempDir.resolve("only_review.md"));
        assertTrue(report.toLowerCase().contains("warning"), "should warn about single-sample input:\n" + report);
    }
}
