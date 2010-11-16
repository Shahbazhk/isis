/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */


package org.apache.isis.core.runtime.options.standard;

import static org.apache.isis.core.runtime.runner.Constants.REFLECTOR_LONG_OPT;
import static org.apache.isis.core.runtime.runner.Constants.REFLECTOR_OPT;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.isis.core.metamodel.config.ConfigurationBuilder;
import org.apache.isis.core.metamodel.specloader.ObjectReflectorInstaller;
import org.apache.isis.core.runtime.installers.InstallerRepository;
import org.apache.isis.core.runtime.runner.BootPrinter;
import org.apache.isis.core.runtime.runner.Constants;
import org.apache.isis.core.runtime.runner.options.OptionHandlerAbstract;
import org.apache.isis.core.runtime.system.SystemConstants;

public class OptionHandlerReflector extends OptionHandlerAbstract {

	private InstallerRepository installerRepository;
	private String reflector;
	public OptionHandlerReflector(final InstallerRepository installerRepository) {
		this.installerRepository = installerRepository;
	}

	@SuppressWarnings("static-access")
	public void addOption(Options options) {
        Object[] reflectors = installerRepository.getInstallers(ObjectReflectorInstaller.class);
        Option option = OptionBuilder.withArgName("name|class name").hasArg().withLongOpt(REFLECTOR_LONG_OPT).withDescription(
                "reflector to use (ignored if type is prototype or client): " + availableInstallers(reflectors)
                        + "; or class name").create(REFLECTOR_OPT);
        options.addOption(option);
		
	}

	public boolean handle(CommandLine commandLine, BootPrinter bootPrinter, Options options) {
		reflector = commandLine.getOptionValue(Constants.REFLECTOR_OPT);
		return true;
	}
	
	public void primeConfigurationBuilder(
			ConfigurationBuilder configurationBuilder) {
		configurationBuilder.add(SystemConstants.REFLECTOR_KEY, reflector);
	}


}
