package com.eurobuddha.mail;
import com.eurobuddha.comms.CommsIdentity;
import org.junit.Test;
import static org.junit.Assert.*;
public class IdentityRecordTest {
    @Test public void restoredIdentitySurvivesSerialization() throws Exception {
        byte[] box=new byte[32],sign=new byte[32],secret=new byte[64]; box[0]=7;sign[31]=9;secret[17]=42;
        CommsIdentity a=CommsIdentity.fromKeys(box,new byte[32],sign,secret);
        CommsIdentity b=IdentityRecord.decode(IdentityRecord.encode(a));
        assertEquals(a.publicId(),b.publicId());assertArrayEquals(a.boxSk,b.boxSk);assertArrayEquals(a.signSk,b.signSk);
    }
    @Test(expected=Exception.class) public void missingKeysRefused() throws Exception { IdentityRecord.decode("{}"); }
    @Test(expected=Exception.class) public void corruptRecordRefused() throws Exception { IdentityRecord.decode("broken"); }
    @Test(expected=IllegalArgumentException.class) public void invalidKeysRefused() throws Exception {
        IdentityRecord.encode(CommsIdentity.fromKeys(new byte[1],new byte[32],new byte[32],new byte[64]));
    }
}
