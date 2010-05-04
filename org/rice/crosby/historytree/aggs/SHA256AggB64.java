package org.rice.crosby.historytree.aggs;

import org.rice.crosby.historytree.AggregationInterface;

import com.google.protobuf.ByteString;
import com.sun.org.apache.xml.internal.security.exceptions.Base64DecodingException;
import com.sun.org.apache.xml.internal.security.utils.Base64;

/** Extend the prior standard SHA256Agg class to be human readable base64'ed values */
@SuppressWarnings("unchecked")
public class SHA256AggB64 extends SHA256Agg {
	@Override
	public byte[] parseAgg(ByteString b) {
		try {
			return Base64.decode(b.toByteArray());
		} catch (Base64DecodingException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public ByteString serializeAgg(byte[] agg) {
		return ByteString.copyFromUtf8(Base64.encode(agg));
	}

	@Override
	public String getName() {
		return NAME;
	}
	static final String NAME = "SHA256AggB64";
	static { 
		AggRegistry.register(new AggregationInterface.Factory() {
			public String name() {return NAME;}
			public AggregationInterface newInstance() { return new ConcatAgg();} 
		});
	}
}