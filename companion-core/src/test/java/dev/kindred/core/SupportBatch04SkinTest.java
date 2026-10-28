package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch04SkinTest {

    private byte[] png(int height,boolean wide)throws Exception{
        var image=new java.awt.image.BufferedImage(64,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        if(wide)image.setRGB(54,20,0xFF112233);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Test void normalizationIsLocaleIndependentAndReservesDefault() {
        var before=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("iris",SkinFiles.keyOf(" IRIS.PNG "));}finally{Locale.setDefault(before);}
        assertEquals("a_b-c",SkinFiles.keyOf("__a..b-c__.png"));assertEquals("",SkinFiles.keyOf("--DEFAULT__.png"));
        assertEquals(32,SkinFiles.keyOf("x".repeat(100)+".png").length());assertEquals("",SkinFiles.keyOf(null));
    }
    @Test void invalidHeadersAndUnboundedSizesAreRefusedBeforeDecode()throws Exception {
        byte[] bytes=png(64,false);java.nio.ByteBuffer.wrap(bytes,16,8).putInt(Integer.MAX_VALUE).putInt(Integer.MAX_VALUE);
        assertFalse(SkinFiles.read("bomb.png",bytes).usable());assertFalse(SkinFiles.read("empty.png",null).usable());
        assertFalse(SkinFiles.read("huge.png",new byte[SkinFiles.MAX_BYTES+1]).usable());
        assertFalse(SkinFiles.read("truncated.png",Arrays.copyOf(png(64,false),23)).usable());
    }
    @Test void validSizeHeaderDoesNotMakeCorruptImageUsable()throws Exception {
        var data=png(64,false);Arrays.fill(data,40,data.length,(byte)7);assertEquals("unreadable PNG",SkinFiles.read("broken.png",data).problem());
    }
    @Test void suffixOverridesAutoDetectionAndLegacyDefaultIsWide()throws Exception {
        assertFalse(SkinFiles.read("auto.png",png(64,true)).skin().slim());assertTrue(SkinFiles.read("auto.png",png(64,false)).skin().slim());
        assertTrue(SkinFiles.read("forced-slim.png",png(64,true)).skin().slim());assertFalse(SkinFiles.read("forced_classic.png",png(64,false)).skin().slim());
        assertFalse(SkinFiles.read("old.png",png(32,false)).skin().slim());
    }
    @Test void inputMutationCannotChangeAcceptedBytesOrHash()throws Exception {
        var input=png(64,false);var accepted=SkinFiles.read("fixture.png",input).skin();var expected=input.clone();Arrays.fill(input,(byte)0);
        assertArrayEquals(expected,accepted.png());assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(expected)),accepted.sha1());
        // Skin's byte[] accessor is currently mutable; hardening it is a separate production change.
    }

}
