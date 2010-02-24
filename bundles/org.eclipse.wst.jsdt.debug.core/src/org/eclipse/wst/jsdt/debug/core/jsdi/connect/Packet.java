/*******************************************************************************
 * Copyright (c) 2010 IBM Corporation and others All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.wst.jsdt.debug.core.jsdi.connect;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Assert;
import org.eclipse.wst.jsdt.debug.core.jsdi.json.JSONConstants;

/**
 * Abstract description of a packet for sending / receiving information to the debug client
 * using JSON
 *  
 *  @since 1.0
 */
abstract public class Packet {

	private static int currentSequence = 0;
	private final int sequence;
	private final String type;

	/**
	 * Constructor
	 * 
	 * @param type the type for the {@link Packet} <code>null</code> is not accepted
	 */
	protected Packet(String type) {
		Assert.isNotNull(type);
		this.sequence = nextSequence();
		this.type = type.intern();
	}

	/**
	 * Constructor
	 * 
	 * @param json the pre-composed map of attributes for the packet, <code>null</code> is not accepted
	 */
	protected Packet(Map json) {
		Assert.isNotNull(json);
		Number packetSeq = (Number) json.get(JSONConstants.SEQ);
		this.sequence = packetSeq.intValue();
		String packetType = (String) json.get(JSONConstants.TYPE);
		this.type = packetType.intern();
	}

	/**
	 * @return a next value for the sequence
	 */
	private static synchronized int nextSequence() {
		return ++currentSequence;
	}

	/**
	 * @return the current sequence
	 */
	public int getSequence() {
		return sequence;
	}

	/**
	 * Returns the type of this packet.<br>
	 * <br>
	 * This method cannot return <code>null</code>
	 * 
	 * @return the type, never <code>null</code>
	 */
	public String getType() {
		return type;
	}

	/**
	 * Returns the type and sequence composed in a JSON map.<br>
	 * <br>
	 * This method cannot return <code>null</code>
	 * @return the composed JSON map
	 */
	public Map toJSON() {
		Map json = new HashMap();
		json.put(JSONConstants.SEQ, new Integer(sequence));
		json.put(JSONConstants.TYPE, type);
		return json;
	}

	/**
	 * Returns the type from the given JSON map.<br>
	 * <br>
	 * This method can return <code>null</code> if the map is not correctly
	 * formed.
	 * 
	 * @param json the JSON map, <code>null</code> is not accepted
	 * @return the type from the JSON map or <code>null</code>
	 */
	public static String getType(Map json) {
		Assert.isNotNull(json);
		return (String) json.get(JSONConstants.TYPE);
	}

}
