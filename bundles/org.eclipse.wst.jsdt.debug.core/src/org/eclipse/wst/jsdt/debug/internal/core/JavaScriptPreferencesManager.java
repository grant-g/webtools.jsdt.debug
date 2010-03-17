/*******************************************************************************
 * Copyright (c) 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.wst.jsdt.debug.internal.core;

import java.util.ArrayList;
import java.util.HashMap;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.wst.jsdt.debug.core.breakpoints.IJavaScriptBreakpoint;
import org.eclipse.wst.jsdt.debug.core.breakpoints.IJavaScriptLoadBreakpoint;
import org.eclipse.wst.jsdt.debug.core.model.JavaScriptDebugModel;
import org.eclipse.wst.jsdt.debug.internal.core.breakpoints.JavaScriptLoadBreakpoint;
import org.eclipse.wst.jsdt.debug.internal.core.model.JavaScriptDebugTarget;

/**
 * Manager to handle various preferences.<br>
 * <br>
 * Specifically this manager handles the preferences for:
 * <ul>
 * <li>Suspend on all script loads</li>
 * </ul>
 * 
 * @since 1.0
 */
public class JavaScriptPreferencesManager implements IPreferenceChangeListener {

	/**
	 * The invisible "suspend on all script loads" breakpoint
	 */
	private static IJavaScriptLoadBreakpoint allLoadsBreakpoint = null;
	
	/**
	 * Starts the manager
	 */
	public void start() {
		IEclipsePreferences node = new InstanceScope().getNode(JavaScriptDebugPlugin.PLUGIN_ID);
		node.addPreferenceChangeListener(this);
		if(node.getBoolean(Constants.SUSPEND_ON_ALL_SCRIPT_LOADS, false)) {
			allLoadsBreakpoint = createSuspendOnAllLoads();
		}
	}
	
	/**
	 * Stops the manager and clean up
	 */
	public void stop() {
		new InstanceScope().getNode(JavaScriptDebugPlugin.PLUGIN_ID).removePreferenceChangeListener(this);
		try {
			if(allLoadsBreakpoint != null) {
				allLoadsBreakpoint.delete();
			}
		} catch (CoreException e) {
			JavaScriptDebugPlugin.log(e);
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener#preferenceChange(org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent)
	 */
	public void preferenceChange(PreferenceChangeEvent event) {
		if(event.getKey().equals(Constants.SUSPEND_ON_ALL_SCRIPT_LOADS)) {
			if(event.getNewValue().equals(Boolean.TRUE.toString()) && allLoadsBreakpoint == null) {
				//create it
				allLoadsBreakpoint = createSuspendOnAllLoads();
			}
			else {
				deleteSuspendOnAllLoads();
			}
		}
	}
	
	/**
	 * Deletes the "suspend on all script loads" breakpoint and notifies debug targets
	 */
	private void deleteSuspendOnAllLoads() {
		if(allLoadsBreakpoint != null) {
			//notify all the targets
			IDebugTarget[] targets = DebugPlugin.getDefault().getLaunchManager().getDebugTargets();
			for (int i = 0; i < targets.length; i++) {
				if(targets[i] instanceof JavaScriptDebugTarget) {
					((JavaScriptDebugTarget)targets[i]).breakpointRemoved(allLoadsBreakpoint, null);
				}
			}
			try {
				allLoadsBreakpoint.delete();
			} catch (CoreException e) {
				JavaScriptDebugPlugin.log(e);
			}
			finally {
				allLoadsBreakpoint = null;
			}
		}
	}
	
	/**
	 * Creates the "suspend on all script loads" breakpoint and notifies all active debug targets 
	 * @return the "suspend on all script loads" breakpoint or <code>null</code>
	 */
	private IJavaScriptLoadBreakpoint createSuspendOnAllLoads() {
		IJavaScriptLoadBreakpoint breakpoint = null;
		try {
			HashMap map = new HashMap();
			map.put(JavaScriptLoadBreakpoint.GLOBAL_SUSPEND, Boolean.TRUE);
			breakpoint = JavaScriptDebugModel.createScriptLoadBreakpoint(
												ResourcesPlugin.getWorkspace().getRoot(), 
												-1, 
												-1, 
												map, 
												false);
		} catch (DebugException e) {
			JavaScriptDebugPlugin.log(e);
		}
		if(breakpoint != null) {
			//notify all the targets
			IDebugTarget[] targets = DebugPlugin.getDefault().getLaunchManager().getDebugTargets();
			for (int i = 0; i < targets.length; i++) {
				if(targets[i] instanceof JavaScriptDebugTarget) {
					((JavaScriptDebugTarget)targets[i]).breakpointAdded(breakpoint);
				}
			}
		}
		return breakpoint;
	}
	
	/**
	 * Returns the complete list of breakpoints that this manager
	 * creates or an empty list, never <code>null</code>
	 * @return the listing of managed breakpoints never <code>null</code>
	 */
	public static IJavaScriptBreakpoint[] getAllManagedBreakpoints() {
		ArrayList breakpoints = new ArrayList();
		if(allLoadsBreakpoint != null) {
			breakpoints.add(allLoadsBreakpoint);
		}
		return (IJavaScriptBreakpoint[]) breakpoints.toArray(new IJavaScriptBreakpoint[breakpoints.size()]);
	}
	
}