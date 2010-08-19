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
package org.eclipse.wst.jsdt.debug.internal.rhino.ui.launching;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.text.CharacterIterator;
import java.text.DateFormat;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate2;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdt.internal.launching.StandardVMType;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.SocketUtil;
import org.eclipse.osgi.util.NLS;
import org.eclipse.wst.jsdt.core.IJavaScriptModelMarker;
import org.eclipse.wst.jsdt.core.IJavaScriptProject;
import org.eclipse.wst.jsdt.core.ITypeRoot;
import org.eclipse.wst.jsdt.core.JavaScriptCore;
import org.eclipse.wst.jsdt.debug.core.jsdi.VirtualMachine;
import org.eclipse.wst.jsdt.debug.core.jsdi.connect.AttachingConnector;
import org.eclipse.wst.jsdt.debug.core.jsdi.connect.Connector;
import org.eclipse.wst.jsdt.debug.internal.core.model.JavaScriptDebugTarget;
import org.eclipse.wst.jsdt.debug.internal.rhino.jsdi.connect.HostArgument;
import org.eclipse.wst.jsdt.debug.internal.rhino.jsdi.connect.PortArgument;
import org.eclipse.wst.jsdt.debug.internal.rhino.jsdi.connect.RhinoAttachingConnector;
import org.eclipse.wst.jsdt.debug.internal.rhino.ui.ILaunchConstants;
import org.eclipse.wst.jsdt.debug.internal.rhino.ui.RhinoUIPlugin;
import org.eclipse.wst.jsdt.debug.internal.rhino.ui.refactoring.Refactoring;
import org.mozilla.javascript.JavaScriptException;
import org.osgi.framework.Bundle;

/**
 * A launch delegate to support launching scripts in a single configuration launch
 * 
 * @since 1.0
 */
public class RhinoLocalLaunchDelegate implements ILaunchConfigurationDelegate2 {

	/**
	 * Polls for connecting to the Rhino interpreter
	 */
	class ConnectRunnable implements Runnable {

		VirtualMachine vm = null;
		Exception exception = null;
		private AttachingConnector connector = null;
		private Map args = null;
		
		ConnectRunnable(AttachingConnector connector, Map args) {
			this.connector = connector;
			this.args = args;
		}
		/* (non-Javadoc)
		 * @see java.lang.Runnable#run()
		 */
		public void run() {
			try {
				long start = System.currentTimeMillis();
				Exception inner = null;
				do {
					try {
						vm = connector.attach(args);
					}
					catch(Exception e) {
						inner = e;
					}
				} while(vm == null && System.currentTimeMillis() < start + 10000);
				if(vm == null) {
					throw inner;
				}
			}
			catch(Exception e) {
				exception = e;
			}
		}
	}
	
	class Filter implements FileFilter {
		public boolean accept(File pathname) {
			return pathname.isDirectory() || JavaScriptCore.isJavaScriptLikeFileName(pathname.getName());
		}
	}
	
	/**
	 * The name of the main class to run
	 * <br><br>
	 * Value is: <code>"org.eclipse.wst.jsdt.debug.rhino.debugger.shell.DebugShell"</code>
	 */
	public static final String DEBUG_SHELL_CLASS = "org.eclipse.wst.jsdt.debug.rhino.debugger.shell.DebugShell"; //$NON-NLS-1$
	/**
	 * The name of the main class to run
	 * <br><br>
	 * Value is: <code>"org.mozilla.javascript.tools.shell.Main"</code>
	 */
	public static final String RHINO_MAIN_CLASS = "org.mozilla.javascript.tools.shell.Main"; //$NON-NLS-1$
	/**
	 * The symbolic name of the Mozilla Rhino bundle
	 * <br><br>
	 * Value is: <code>org.mozilla.javascript</code>
	 */
	public static final String MOZILLA_JAVASCRIPT_BUNDLE = "org.mozilla.javascript"; //$NON-NLS-1$
	/**
	 * The symbolic name of the debug transport bundle
	 * <br><br>
	 * Value is: <code>org.eclipse.wst.jsdt.debug.transport</code>
	 */
	public static final String DEBUG_TRANSPORT_BUNDLE = "org.eclipse.wst.jsdt.debug.transport"; //$NON-NLS-1$
	/**
	 * The symbolic name of the Rhino debug interface bundle
	 * <br><br>
	 * Value is: <code>org.eclipse.wst.jsdt.debug.rhino.debugger</code>
	 */
	private static final String RHINO_DEBUGGER_BUNDLE = "org.eclipse.wst.jsdt.debug.rhino.debugger"; //$NON-NLS-1$
	
	/**
	 * Array of the bundles required to launch
	 */
	public static final String[] REQUIRED_BUNDLES = {MOZILLA_JAVASCRIPT_BUNDLE, DEBUG_TRANSPORT_BUNDLE, RHINO_DEBUGGER_BUNDLE};
	
	private Filter filefilter = new Filter();
	
	private ArrayList scope = null;
	private IJavaScriptProject project = null;
	private ITypeRoot script = null;
	
	synchronized IJavaScriptProject getProject(ILaunchConfiguration configuration) throws CoreException {
		if(project == null) {
			IProject p = Refactoring.getProject(configuration);
			this.project = JavaScriptCore.create(p);
		}
		return this.project;
	}
	
	synchronized ITypeRoot getScript(ILaunchConfiguration configuration) throws CoreException {
		if(this.script == null) {
			IResource resource = Refactoring.getScript(configuration, null);
			if(resource != null) {
				this.script = (ITypeRoot) JavaScriptCore.create((IFile)resource);
			}
		}
		return this.script;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ILaunchConfigurationDelegate#launch(org.eclipse.debug.core.ILaunchConfiguration, java.lang.String, org.eclipse.debug.core.ILaunch, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public void launch(ILaunchConfiguration configuration, String mode,	ILaunch launch, IProgressMonitor monitor) throws CoreException {
		SubMonitor localmonitor = SubMonitor.convert(monitor, NLS.bind(Messages.launching__, configuration.getName()), 8);
		int port = SocketUtil.findFreePort();
		localmonitor.subTask(Messages.starting_rhino_interpreter);
		//launch the interpreter
		if(localmonitor.isCanceled()) {
			return;
		}
		localmonitor.worked(1);
		VirtualMachine vm = null;
		Process p = null;
		try {
			localmonitor.subTask(Messages.configuring_rhino_debugger);
			if(localmonitor.isCanceled()) {
				return;
			}
			localmonitor.worked(1);
			//compute commandline args
			ArrayList cmdargs = new ArrayList();
			addVMArgs(configuration, cmdargs);
			addConnectionArgs(port, cmdargs);
			addECMAVersion(configuration, cmdargs);
			addEncoding(launch, cmdargs);
			addOptLevel(configuration, cmdargs);
			addStrictMode(configuration, cmdargs);
			addLoadArg(cmdargs);
			
			p = DebugPlugin.exec((String[]) cmdargs.toArray(new String[cmdargs.size()]), null);
			HashMap args = new HashMap();
			args.put(HostArgument.HOST, "localhost"); //$NON-NLS-1$
			args.put(PortArgument.PORT, Integer.toString(port));
			
			localmonitor.subTask(Messages.creating_rhino_vm);
			if(localmonitor.isCanceled()) {
				cancel(vm, p);
				return;
			}
			localmonitor.worked(1);
			RhinoAttachingConnector connector = new RhinoAttachingConnector();
			ConnectRunnable runnable = new ConnectRunnable(connector, args);
			Thread thread = new Thread(runnable, Messages.connect_thread);
			thread.setDaemon(true);
			thread.start();
			while(thread.isAlive()) {
				if(localmonitor.isCanceled()) {
					cancel(vm, p);
					thread.interrupt();
				}
				try {
					Thread.sleep(100);
				}
				catch (Exception e) {
				}
			}
			if(runnable.exception != null) {
				throw runnable.exception;
			}
			if(runnable.vm == null) {
				throw new IOException("Failed to connect to Rhino interpreter."); //$NON-NLS-1$
			}
			vm = runnable.vm;
			localmonitor.subTask(Messages.starting_rhino_process);
			if(localmonitor.isCanceled()) {
				cancel(vm, p);
				return;
			}
			localmonitor.worked(1);
			RhinoProcess process = new RhinoProcess(launch, p, computeProcessName(connector, args));
			process.setAttribute(IProcess.ATTR_CMDLINE, formatCommandlineArgs(cmdargs));
			launch.addProcess(process);
			localmonitor.subTask(Messages.creating_js_debug_target);
			if(localmonitor.isCanceled()) {
				cancel(vm, p);
				return;
			}
			localmonitor.worked(1);
			JavaScriptDebugTarget target = new JavaScriptDebugTarget(vm, process, launch, true, false);
			launch.addDebugTarget(target);
			if(localmonitor.isCanceled()) {
				cancel(vm, p);
				return;
			}
			localmonitor.worked(1);
			return;
		}
		catch(IOException ioe) {
			cancel(vm, p);
			RhinoUIPlugin.log(ioe);
		}
		catch(Exception e) {
			if(e instanceof JavaScriptException) {
				//ignore certain exceptions from code
				if(configuration.getAttribute(ILaunchConstants.ATTR_LOG_INTERPRETER_EXCEPTIONS, true)) {
					RhinoUIPlugin.log(e);
				}
				return;
			}
			cancel(vm, p);
			RhinoUIPlugin.log(e);
		}
		finally {
			if(scope != null) {
				scope.clear();
				scope = null;
			}
			project = null;
			script = null;
		}
		launch.terminate();
		return;
	}

	/**
	 * Stops + cleans up on cancellation / failure
	 * 
	 * @param vm
	 * @param process
	 */
	void cancel(VirtualMachine vm, Process process) {
		if(process != null) {
			process.destroy();
		}
		if(vm != null) {
			vm.terminate();
		}
	}
	
	/**
	 * Adds the script scope to the args and a single <code>load()</code> entry
	 * 
	 * @param args
	 */
	void addLoadArg(ArrayList args) {
		args.add("-e"); //$NON-NLS-1$
		StringBuffer buffer = new StringBuffer();
		buffer.append("load("); //$NON-NLS-1$
		for (Iterator i = scope.iterator(); i.hasNext();) {
			buffer.append(i.next());
			if(i.hasNext()) {
				buffer.append(',');
			}
		}
		buffer.append(")"); //$NON-NLS-1$
		args.add(buffer.toString());
	}
	
	/**
	 * Adds the VM executable location and classpath entries to the given arguments list
	 * 
	 * @param configuration
	 * @param args
	 * @throws CoreException
	 * @throws IOException 
	 */
	void addVMArgs(ILaunchConfiguration configuration, ArrayList args) throws CoreException, IOException {
		IVMInstall vm = JavaRuntime.getDefaultVMInstall();
		File loc = vm.getInstallLocation();
		if(loc == null) {
			throw new CoreException(new Status(IStatus.ERROR, RhinoUIPlugin.PLUGIN_ID, "Could not locate the default VM install")); //$NON-NLS-1$
		}
		File exe = StandardVMType.findJavaExecutable(vm.getInstallLocation());
		if(exe == null) {
			throw new CoreException(new Status(IStatus.ERROR, RhinoUIPlugin.PLUGIN_ID, "Could not find the Java executable for the default VM install")); //$NON-NLS-1$
		}
		args.add(exe.getAbsolutePath());
		StringBuffer buffer = new StringBuffer();
		for (int i = 0; i < REQUIRED_BUNDLES.length; i++) {
			Bundle bundle = Platform.getBundle(REQUIRED_BUNDLES[i]);
			if(bundle == null) {
				throw new CoreException(new Status(IStatus.ERROR, RhinoUIPlugin.PLUGIN_ID, NLS.bind("Could not locate the {0} bundle", REQUIRED_BUNDLES[i]))); //$NON-NLS-1$
			}
			File file = FileLocator.getBundleFile(bundle);
			if(file.isDirectory()) {
				if(MOZILLA_JAVASCRIPT_BUNDLE.equals(REQUIRED_BUNDLES[i])) {
					buffer.append(escapePath(file, false));
				}
				else {
					//mozilla uses the project as the class file output dir
					//so we only have to include bin directories for the other ones
					file = new File(file, "bin"); //$NON-NLS-1$
					if(file.exists()) {
						buffer.append(escapePath(file, false));
					}
				}
			}
			else {
				buffer.append(escapePath(file, false));
			}
			if(i < REQUIRED_BUNDLES.length-1) {
				appendSep(buffer);
			}
		}
		args.add("-cp"); //$NON-NLS-1$
		args.add(buffer.toString());
		args.add(DEBUG_SHELL_CLASS);
	}
	
	/**
	 * Escapes the path of the given file.
	 * 
	 * @param file the file to escape the path for
	 * @return the escaped path
	 */
	String escapePath(File file, boolean singlequote) {
		String path = file.getAbsolutePath();
		StringCharacterIterator iter = new StringCharacterIterator(path);
		StringBuffer buffer = new StringBuffer();
		boolean hasspace = false;
		char c = iter.current();
		while(c != CharacterIterator.DONE) {
			if(c == '\\') {
				buffer.append("/"); //$NON-NLS-1$
			}
			else if(c == '"') {
				buffer.append("\""); //$NON-NLS-1$
			}
			else if(c == ' ') {
				hasspace = true;
				buffer.append(c);
			}
			else {
				buffer.append(c);
			}
			c = iter.next();
		}
		if(singlequote) {
			buffer.insert(0, '\'');
			buffer.append('\'');
			return buffer.toString();
		}
		else if(hasspace){
			buffer.insert(0, "\""); //$NON-NLS-1$
			buffer.append("\""); //$NON-NLS-1$
			return buffer.toString();
		}
		return path;
	}
	
	/**
	 * Appends the correct version of a classpath separator to the given buffer
	 * 
	 * @param buffer the buffer to add the separator to
	 */
	void appendSep(StringBuffer buffer) {
		if(Platform.getOS().equals(Platform.OS_WIN32)) {
			buffer.append(';');
		}
		else {
			buffer.append(':');
		}
	}
	
	/**
	 * Adds the default connection string to the command line arguments
	 * 
	 * @param port
	 * @param args
	 */
	void addConnectionArgs(int port, ArrayList args) {
		args.add("-port"); //$NON-NLS-1$
		args.add(Integer.toString(port));
		args.add("-suspend"); //$NON-NLS-1$
		args.add("y"); //$NON-NLS-1$
	}
	
	/**
	 * Adds the -version NNN ECMA version to the interpreter command line options
	 * 
	 * @param configuration
	 * @param args
	 * @throws CoreException
	 */
	void addECMAVersion(ILaunchConfiguration configuration, ArrayList args) throws CoreException {
		args.add("-version"); //$NON-NLS-1$
		args.add(configuration.getAttribute(ILaunchConstants.ATTR_ECMA_VERSION, "170")); //$NON-NLS-1$
	}
	
	/**
	 * Adds the encoding from the launch to the command line
	 * 
	 * @param launch
	 * @param args
	 */
	void addEncoding(ILaunch launch, ArrayList args) {
		String encoding = launch.getAttribute(DebugPlugin.ATTR_CONSOLE_ENCODING);
		if(encoding != null) {
			args.add("-encoding"); //$NON-NLS-1$
			args.add(encoding);
		}
	}
	
	/**
	 * Adds the optimization level to the interpreter
	 * @param configuration
	 * @param args
	 * @throws CoreException
	 */
	void addOptLevel(ILaunchConfiguration configuration, ArrayList args) throws CoreException {
		int opt = configuration.getAttribute(ILaunchConstants.ATTR_OPT_LEVEL, -1);
		args.add("-opt"); //$NON-NLS-1$
		args.add(Integer.toString(opt));
	}
	
	/**
	 * Adds the <code>-strict</code> flag if set in the configuration
	 * 
	 * @param configuration
	 * @param args
	 * @throws CoreException
	 */
	void addStrictMode(ILaunchConfiguration configuration, ArrayList args) throws CoreException {
		boolean strict = configuration.getAttribute(ILaunchConstants.ATTR_STRICT_MODE, false);
		if(strict) {
			args.add("-strict"); //$NON-NLS-1$
		}
	}
	
	/**
	 * Computes the complete list of scripts to load, with the last loaded script
	 * being the one desired 
	 * 
	 * @param configuration
	 * @param monitor
	 * @throws CoreException 
	 */
	synchronized void computeScriptScope(ILaunchConfiguration configuration, IProgressMonitor monitor) throws CoreException {
		//for now we only load the script you want to debug and the ones reported from the JS project as source containers
		if(this.scope == null) {
			this.scope = new ArrayList();
			List list = configuration.getAttribute(ILaunchConstants.ATTR_INCLUDE_PATH, (List)null);
			if(list != null) {
				boolean subdirs = configuration.getAttribute(ILaunchConstants.ATTR_INCLUDE_PATH_SUB_DIRS, false);
				String entry = null;
				for (Iterator i = list.iterator(); i.hasNext();) {
					entry = (String) i.next();
					int kind = Integer.parseInt(entry.substring(0, 1));
					switch(kind) {
						case IncludeTab.SCRIPT: {
							String value = entry.substring(1);
							if(!scope.contains(value)) {
								scope.add(value);
							}
							continue;
						}
						case IncludeTab.FOLDER: {
							String f = entry.substring(1);
							IContainer folder = (IContainer) ResourcesPlugin.getWorkspace().getRoot().findMember(f);
							if(folder != null) {
								resolveScriptsFrom(folder, this.scope, subdirs);
							}
							continue;
						}
						case IncludeTab.EXT_FOLDER: {
							String f = entry.substring(1);
							File folder = new File(f);
							if(folder.exists()) {
								resolveScriptsFrom(folder, this.scope, subdirs);
							}
							continue;
						}
					}
				}
			}
			addScriptAttribute(configuration, scope);
		}
	}
	
	/**
	 * Retrieves the complete listing of scripts in the given {@link File} and optionally recursively 
	 * adds scripts from sub directories as well
	 * @param folder
	 * @param scripts
	 * @param subdirs
	 */
	void resolveScriptsFrom(File folder, List scripts, boolean subdirs) {
		File[] files = folder.listFiles(filefilter);
		for (int i = 0; i < files.length; i++) {
			if(files[i].isDirectory() && subdirs) {
				resolveScriptsFrom(files[i], scripts, subdirs);
			}
			else {
				String path = escapePath(files[i], true);
				if(!scripts.contains(path)) {
					scripts.add(path);
				}
			}
		}
	}
	
	/**
	 * Retrieves the complete listing of scripts in the given {@link IFolder} and optionally recursively 
	 * adds scripts from sub directories as well
	 * 
	 * @param folder
	 * @param scripts
	 * @param subdirs
	 * @throws CoreException
	 */
	void resolveScriptsFrom(IContainer folder, List scripts, boolean subdirs) throws CoreException {
		IResource[] members = folder.members();
		for (int i = 0; i < members.length; i++) {
			switch(members[i].getType()) {
				case IResource.FILE: {
					IFile file = (IFile) members[i];
					if(JavaScriptCore.isJavaScriptLikeFileName(file.getName())) {
						File ffile = URIUtil.toFile(file.getLocationURI());
						String value = escapePath(ffile, true);
						if(!scripts.contains(value)) {
							scripts.add(value);
						}
					}
					continue;
				}
				case IResource.FOLDER: {
					if(subdirs) {
						resolveScriptsFrom((IContainer) members[i], scripts, subdirs);
					}
					continue;
				}
			}
		}
	}
	
	/**
	 * Adds the absolute path of the script specified in the launch configuration to the listing of arguments
	 * 
	 * @param configuration
	 * @param args
	 * @throws CoreException
	 */
	void addScriptAttribute(ILaunchConfiguration configuration, ArrayList args) throws CoreException {
		ITypeRoot root = getScript(configuration);
		File file = URIUtil.toFile(root.getResource().getLocationURI());
		String value = escapePath(file, true);
		if(args.remove(value)) {
			//want to make sure it is interpreted last
			args.add(value);
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ILaunchConfigurationDelegate2#getLaunch(org.eclipse.debug.core.ILaunchConfiguration, java.lang.String)
	 */
	public ILaunch getLaunch(ILaunchConfiguration configuration, String mode) throws CoreException {
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ILaunchConfigurationDelegate2#buildForLaunch(org.eclipse.debug.core.ILaunchConfiguration, java.lang.String, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public boolean buildForLaunch(ILaunchConfiguration configuration, String mode, IProgressMonitor monitor) throws CoreException {
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ILaunchConfigurationDelegate2#finalLaunchCheck(org.eclipse.debug.core.ILaunchConfiguration, java.lang.String, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public boolean finalLaunchCheck(ILaunchConfiguration configuration, String mode, IProgressMonitor monitor) throws CoreException {
		return true;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ILaunchConfigurationDelegate2#preLaunchCheck(org.eclipse.debug.core.ILaunchConfiguration, java.lang.String, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public boolean preLaunchCheck(ILaunchConfiguration configuration, String mode, IProgressMonitor monitor) throws CoreException {
		monitor.subTask(Messages.computing_script_scope);
		computeScriptScope(configuration, monitor);
		if(this.scope.isEmpty()) {
			this.scope = null;
			throw new CoreException(new Status(IStatus.ERROR, RhinoUIPlugin.PLUGIN_ID, Messages.failed_to_compute_scope));
		}
		return true;
	}
	
	/**
	 * Returns if the script to launch has any JavaScript problems
	 * 
	 * @param configuration
	 * @return
	 * @throws CoreException
	 */
	boolean hasProblems(ILaunchConfiguration configuration) throws CoreException {
		//TODO this should be expanded to check the entire script scope
		String name = configuration.getAttribute(ILaunchConstants.ATTR_SCRIPT, ILaunchConstants.EMPTY_STRING);
		IResource script = ResourcesPlugin.getWorkspace().getRoot().findMember(new Path(name));
		if(script.isAccessible()) {
			IMarker[] problems = script.findMarkers(IJavaScriptModelMarker.JAVASCRIPT_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE);
			return problems.length > 0;
		}
		return false;
	}
	
	/**
	 * Turns the command line argument list into a string
	 * 
	 * @param args
	 * @return the command line list as a string
	 */
	String formatCommandlineArgs(ArrayList args) {
		StringBuffer buffer = new StringBuffer();
		for (int i = 0; i < args.size(); i++) {
			buffer.append(args.get(i));
			if(i < args.size()-1) {
				buffer.append(" "); //$NON-NLS-1$
			}
		}
		return buffer.toString();
	}
	
	/**
	 * Computes the command line to set on the process attribute
	 * @param args
	 * @return
	 */
	String formatConnectionArguments(Map args) {
		StringBuffer buffer = new StringBuffer();
		Map.Entry entry = null;
		for(Iterator iter = args.entrySet().iterator(); iter.hasNext();) {
			entry = (Entry) iter.next();
			buffer.append(entry.getKey()).append(':').append(entry.getValue());
			if(iter.hasNext()) {
				buffer.append(',');
			}
		}
		return buffer.toString();
	}
	
	/**
	 * Computes the display name for the {@link IProcess} given the connector
	 * @param connector
	 * @return the name for the process
	 */
	String computeProcessName(Connector connector, Map args) {
		StringBuffer buffer = new StringBuffer(connector.name());
		String timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(System.currentTimeMillis()));
		return NLS.bind(Messages.process_label, new String[] {buffer.toString(), formatConnectionArguments(args), timestamp});
	}
}
