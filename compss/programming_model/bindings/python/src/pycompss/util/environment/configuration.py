#!/usr/bin/python
#
#  Copyright 2002-2019 Barcelona Supercomputing Center (www.bsc.es)
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

# -*- coding: utf-8 -*-

"""
PyCOMPSs Util - configurators
=============================
    This file contains the configurator methods.
    Currently it is used by interactive.py and launch.py
"""

import os
import sys
import logging
from tempfile import mkstemp
import pycompss.runtime.binding as binding
from pycompss.util.supercomputer.scs import get_master_node
from pycompss.util.supercomputer.scs import get_master_port
from pycompss.util.supercomputer.scs import get_xmls
from pycompss.util.supercomputer.scs import get_uuid
from pycompss.util.supercomputer.scs import get_base_log_dir
from pycompss.util.supercomputer.scs import get_specific_log_dir
from pycompss.util.supercomputer.scs import get_log_level
from pycompss.util.supercomputer.scs import get_tracing
from pycompss.util.supercomputer.scs import get_storage_conf
from pycompss.util.logger.helpers import init_logging

DEFAULT_PROJECT_PATH = '/Runtime/configuration/xml/projects/'
DEFAULT_RESOURCES_PATH = '/Runtime/configuration/xml/resources/'
DEFAULT_LOG_PATH = '/Runtime/configuration/log/'
DEFAULT_TRACING_PATH = '/Runtime/configuration/xml/tracing/'


def prepare_environment(interactive, o_c, storage_impl,
                        app, debug, trace, mpi_worker):
    """
    Setup the environment variable and retrieve their content.

    :param interactive: True | False If the environment is interactive or not.
    :param o_c: Object conversion to string
    :param storage_impl: Storage implementation
    :param app: Application name
    :param debug: True | False If debug is enabled
    :param trace: Trace mode (True | False | 'scorep' | 'arm-map' | 'arm-ddt')
    :param mpi_worker: True | False if mpi worker is enabled
    :return: Dictionary contanining the compss_home, pythonpath, classpath,
             ld_library_path, cp, extrae_home, extrae_lib and file_name values.
    """
    launch_path = os.path.dirname(os.path.realpath(__file__))

    compss_home = ''
    if interactive:
        # compss_home = launch_path without the last 6 folders:
        # (Bindings/python/version/pycompss/util/environment)
        compss_home = os.path.sep.join(launch_path.split(os.path.sep)[:-6])
    os.environ['COMPSS_HOME'] = compss_home

    # Grab the existing PYTHONPATH, CLASSPATH and LD_LIBRARY_PATH environment
    # variables values
    pythonpath = os.getcwd() + ':.'
    if 'PYTHONPATH' in os.environ:
        pythonpath += ':' + os.environ['PYTHONPATH']
    classpath = ''
    if 'CLASSPATH' in os.environ:
        classpath = os.environ['CLASSPATH']
    ld_library_path = ''
    if 'LD_LIBRARY_PATH' in os.environ:
        ld_library_path = os.environ['LD_LIBRARY_PATH']

    # Enable/Disable object to string conversion
    # set cross-module variable
    binding.object_conversion = o_c

    # Get the filename and its path.
    if interactive:
        file_name = 'Interactive'
        cp = os.getcwd() + '/'
    else:
        file_name = os.path.splitext(os.path.basename(app))[0]
        cp = os.path.dirname(app)

    # Set storage classpath
    if storage_impl:
        if storage_impl == 'redis':
            cp = cp + ':' + compss_home + \
                 '/Tools/storage/redis/compss-redisPSCO.jar'
        else:
            cp = cp + ':' + storage_impl

    # Set extrae dependencies
    if "EXTRAE_HOME" not in os.environ:
        # It can be defined by the user or by launch_compss when running
        # in Supercomputer
        extrae_home = compss_home + '/Dependencies/extrae'
        os.environ['EXTRAE_HOME'] = extrae_home
    else:
        extrae_home = os.environ['EXTRAE_HOME']
    extrae_lib = extrae_home + '/lib'

    # Include extrae into ld_library_path
    os.environ['LD_LIBRARY_PATH'] = extrae_lib + ':' + ld_library_path

    if debug:
        # Add environment variable to get binding-commons debug information
        os.environ['COMPSS_BINDINGS_DEBUG'] = '1'

    if trace == 'scorep' or trace == 'arm-map' or trace == 'arm-ddt':
        # Force mpi worker if using ScoreP, ARM-MAP or ARM-DDT
        mpi_worker = True

    env_vars = {'compss_home': compss_home,
                'pythonpath': pythonpath,
                'classpath': classpath,
                'ld_library_path': ld_library_path,
                'cp': cp,
                'extrae_home': extrae_home,
                'extrae_lib': extrae_lib,
                'file_name': file_name,
                'mpi_worker': mpi_worker}
    return env_vars


def prepare_loglevel_graph_for_monitoring(monitor, graph, debug, log_level):
    """
    Checks if monitor is enabled and updates graph and log level accordingly.
    If debug is True, then the log_level is set to debug.

    :param monitor: Monitor refresh frequency. None if disabled.
    :param graph: True | False If graph is enabled or disabled.
    :param debug: True | False If debug is enabled or disabled.
    :param log_level: Defined log level
    :return: Dictionary containing the updated monitor, graph and log_level
             values.
    """
    if monitor is not None:
        # Enable the graph if the monitoring is enabled
        graph = True
        # Set log level info
        log_level = 'info'

    if debug:
        # If debug is enabled, the output is more verbose
        log_level = 'debug'

    monitoring_vars = {'monitor': monitor,
                       'graph': graph,
                       'log_level': log_level}
    return monitoring_vars


def updated_variables_in_sc():
    """
    Retrieve the updated variable values whithin SCs.

    :return: Dictionary containing the updated variables (project_xml,
             resources_xml, master_name, master_port, uuid, base_log_dir,
             specific_log_dir, storage_conf, log_level, debug and trace).
    """
    # Since the deployment in supercomputers is done through the use of
    # enqueue_compss and consequently launch_compss - the project and resources
    # xmls are already created
    project_xml, resources_xml = get_xmls()
    # It also exported some environment variables that we need here
    master_name = get_master_node()
    master_port = get_master_port()
    uuid = get_uuid()
    base_log_dir = get_base_log_dir()
    specific_log_dir = get_specific_log_dir()
    storage_conf = get_storage_conf()
    # Override debug considering the parameter defined in
    # pycompss_interactive_sc script and exported by launch_compss
    log_level = get_log_level()
    if log_level == 'debug':
        debug = True
    else:
        debug = False
    # Override tracing considering the parameter defined in
    # pycompss_interactive_sc script and exported by launch_compss
    trace = get_tracing()
    updated_vars = {'project_xml': project_xml,
                    'resources_xml': resources_xml,
                    'master_name': master_name,
                    'master_port': master_port,
                    'uuid': uuid,
                    'base_log_dir': base_log_dir,
                    'specific_log_dir': specific_log_dir,
                    'storage_conf': storage_conf,
                    'log_level': log_level,
                    'debug': debug,
                    'trace': trace}
    return updated_vars


def prepare_tracing_environment(trace, extrae_lib, ld_library_path):
    """
    Prepare the environment for tracing. Also retrieves the appropriate trace
    value for the initial configuration file (which is an integer)

    :param trace: [ True | basic ] | advanced | False Tracing mode.
    :param extrae_lib: Extrae lib path.
    :param ld_library_path: LD_LIBRARY_PATH environment content
    :return: Trace mode (as integer)
    """
    if trace is False:
        trace_value = 0
    elif trace == 'basic' or trace is True:
        trace_value = 1
        os.environ['LD_PRELOAD'] = extrae_lib + '/libpttrace.so'
        ld_library_path = ld_library_path + ':' + extrae_lib
    elif trace == 'advanced':
        trace_value = 2
        os.environ['LD_PRELOAD'] = extrae_lib + '/libpttrace.so'
        ld_library_path = ld_library_path + ':' + extrae_lib
    else:
        msg = "ERROR: Wrong tracing parameter " + \
              "( [ True | basic ] | advanced | False)"
        raise Exception(msg)
    return trace_value, ld_library_path


def check_infrastructure_variables(project_xml, resources_xml, compss_home,
                                   app_name, file_name, external_adaptation):
    """
    Checks the infrastructure variables and updates them if None.
    :param project_xml: Project xml file path (None if not defined)
    :param resources_xml: Resources xml file path (None if not defined)
    :param compss_home: Compss home path
    :param app_name: Application name (if None, it changes it with filename)
    :param file_name: Application file name
    :param external_adaptation: External adaptation
    :return: Updated variables (project_xml, resources_xml, app_name,
                                external_adaptation, major_version,
                                python_interpreter, python_version and
                                python_virtual_environment)
    """
    if project_xml is None:
        project_xml = compss_home + DEFAULT_PROJECT_PATH + \
                      'default_project.xml'
    if resources_xml is None:
        resources_xml = compss_home + DEFAULT_RESOURCES_PATH + \
                        'default_resources.xml'
    app_name = file_name if app_name is None else app_name
    external_adaptation = 'true' if external_adaptation else 'false'
    major_version = str(sys.version_info[0])
    python_interpreter = 'python' + major_version
    python_version = major_version
    # Check if running within a virtual environment
    if 'VIRTUAL_ENV' in os.environ:
        python_virtual_environment = os.environ['VIRTUAL_ENV']
    else:
        python_virtual_environment = 'null'
    inf_vars = {'project_xml': project_xml,
                'resources_xml': resources_xml,
                'app_name': app_name,
                'external_adaptation': external_adaptation,
                'major_version': major_version,
                'python_interpreter': python_interpreter,
                'python_version': python_version,
                'python_virtual_environment': python_virtual_environment}
    return inf_vars


def create_init_config_file(compss_home,
                            debug,
                            log_level,
                            project_xml,
                            resources_xml,
                            summary,
                            task_execution,
                            storage_conf,
                            streaming_backend,
                            streaming_master_name,
                            streaming_master_port,
                            task_count,
                            app_name,
                            uuid,
                            base_log_dir,
                            specific_log_dir,
                            graph,
                            monitor,
                            trace,
                            extrae_cfg,
                            comm,
                            conn,
                            master_name,
                            master_port,
                            scheduler,
                            cp,
                            classpath,
                            ld_library_path,
                            pythonpath,
                            jvm_workers,
                            cpu_affinity,
                            gpu_affinity,
                            fpga_affinity,
                            fpga_reprogram,
                            profile_input,
                            profile_output,
                            scheduler_config,
                            external_adaptation,
                            python_interpreter,
                            python_version,
                            python_virtual_environment,
                            propagate_virtual_environment,
                            mpi_worker,
                            **kwargs):
    """
    Creates the initialization files for the runtime start (java options file).

    :param compss_home: <String> COMPSs installation path
    :param debug:  <Boolean> Enable/Disable debugging
                   (True|False) (overrides log_level)
    :param log_level: <String> Define the log level
                      ('off' (default) | 'info' | 'debug')
    :param project_xml: <String> Specific project.xml path
    :param resources_xml: <String> Specific resources.xml path
    :param summary: <Boolean> Enable/Disable summary (True|False)
    :param task_execution: <String> Who performs the task execution
                           (normally "compss")
    :param storage_conf: None|<String> Storage configuration file path
    :param streaming_backend: Streaming backend (default: None => 'null')
    :param streaming_master_name: Streaming master name
                                  (default: None => 'null')
    :param streaming_master_port: Streaming master port
                                  (default: None => 'null')
    :param task_count: <Integer> Number of tasks
                       (for structure initialization purposes)
    :param app_name: <String> Application name
    :param uuid: None|<String> Application UUID
    :param base_log_dir: None|<String> Base log path
    :param specific_log_dir: None|<String> Specific log path
    :param graph: <Boolean> Enable/Disable graph generation
    :param monitor: None|<Integer> Disable/Frequency of the monitor
    :param trace: <Boolean> Enable/Disable trace generation. Also accepts
                  String (scorep, arm-map, arm-ddt)
    :param extrae_cfg: None|<String> Default extrae configuration/user
                       specific extrae configuration
    :param comm: <String> GAT/NIO
    :param conn: <String> Connector
                 (normally: es.bsc.compss.connectors.DefaultSSHConnector)
    :param master_name: <String> Master node name
    :param master_port: <String> Master node port
    :param scheduler: <String> Scheduler (normally:
                  es.bsc.compss.scheduler.loadbalancing.LoadBalancingScheduler)
    :param cp: <String>  Application path
    :param classpath: <String> CLASSPATH environment variable contents
    :param ld_library_path: <String> LD_LIBRARY_PATH environment
                            variable contents
    :param pythonpath: <String> PYTHONPATH environment variable contents
    :param jvm_workers: <String> Worker's jvm configuration
                        (example: "-Xms1024m,-Xmx1024m,-Xmn400m")
    :param cpu_affinity: <String> CPU affinity (default: automatic)
    :param gpu_affinity: <String> GPU affinity (default: automatic)
    :param fpga_affinity: <String> FPGA affinity (default: automatic)
    :param fpga_reprogram: <String> FPGA reprogram command (default: '')
    :param profile_input: <String> profiling input
    :param profile_output: <String> profiling output
    :param scheduler_config: <String> Path to the file which contains the
                             scheduler configuration.
    :param external_adaptation: <String> Enable external adaptation.
                                This option will disable the Resource Optimizer
    :param python_interpreter: <String> Python interpreter
    :param python_version: <String> Python interpreter version
    :param python_virtual_environment: <String> Python virtual environment path
    :param propagate_virtual_environment: <Boolean> Propagate python virtual
                                          environment to workers
    :param mpi_worker: Use the MPI worker [ True | False ] (default: False)
    :param kwargs: Any other parameter
    :return: None
    """
    fd, temp_path = mkstemp()
    jvm_options_file = open(temp_path, 'w')

    # JVM GENERAL OPTIONS
    jvm_options_file.write('-XX:+PerfDisableSharedMem\n')
    jvm_options_file.write('-XX:-UsePerfData\n')
    jvm_options_file.write('-XX:+UseG1GC\n')
    jvm_options_file.write('-XX:+UseThreadPriorities\n')
    jvm_options_file.write('-XX:ThreadPriorityPolicy=42\n')
    if debug or log_level == 'debug':
        jvm_options_file.write('-Dlog4j.configurationFile=' +
                               compss_home + DEFAULT_LOG_PATH +
                               'COMPSsMaster-log4j.debug\n')  # DEBUG
    elif monitor is not None or log_level == 'info':
        jvm_options_file.write('-Dlog4j.configurationFile=' +
                               compss_home + DEFAULT_LOG_PATH +
                               'COMPSsMaster-log4j.info\n')   # INFO
    else:
        jvm_options_file.write('-Dlog4j.configurationFile=' +
                               compss_home + DEFAULT_LOG_PATH +
                               'COMPSsMaster-log4j\n')        # NO DEBUG
    jvm_options_file.write('-Dcompss.to.file=false\n')
    jvm_options_file.write('-Dcompss.project.file=' + project_xml + '\n')
    jvm_options_file.write('-Dcompss.resources.file=' + resources_xml + '\n')
    jvm_options_file.write('-Dcompss.project.schema=' +
                           compss_home + DEFAULT_PROJECT_PATH +
                           'project_schema.xsd\n')
    jvm_options_file.write('-Dcompss.resources.schema=' +
                           compss_home + DEFAULT_RESOURCES_PATH +
                           'resources_schema.xsd\n')
    jvm_options_file.write('-Dcompss.lang=python\n')
    if summary:
        jvm_options_file.write('-Dcompss.summary=true\n')
    else:
        jvm_options_file.write('-Dcompss.summary=false\n')
    jvm_options_file.write('-Dcompss.task.execution=' + task_execution + '\n')
    if storage_conf is None:
        jvm_options_file.write('-Dcompss.storage.conf=null\n')
    else:
        jvm_options_file.write('-Dcompss.storage.conf=' + storage_conf + '\n')

    # Add streaming properties
    if streaming_backend is None:
        jvm_options_file.write('-Dcompss.streaming=null\n')
    else:
        jvm_options_file.write('-Dcompss.streaming=' +
                               str(streaming_backend) + '\n')
    if streaming_master_name is None:
        jvm_options_file.write('-Dcompss.streaming.masterName=null\n')
    else:
        jvm_options_file.write('-Dcompss.streaming.masterName=' +
                               str(streaming_master_name) + '\n')
    if streaming_master_port is None:
        jvm_options_file.write('-Dcompss.streaming.masterPort=null\n')
    else:
        jvm_options_file.write('-Dcompss.streaming.masterPort=' +
                               str(streaming_master_port) + '\n')

    jvm_options_file.write('-Dcompss.core.count=' + str(task_count) + '\n')

    jvm_options_file.write('-Dcompss.appName=' + app_name + '\n')

    if uuid is None:
        import uuid
        my_uuid = str(uuid.uuid4())
    else:
        my_uuid = uuid

    jvm_options_file.write('-Dcompss.uuid=' + my_uuid + '\n')

    if base_log_dir is None:
        # It will be within $HOME/.COMPSs
        jvm_options_file.write('-Dcompss.baseLogDir=\n')
    else:
        jvm_options_file.write('-Dcompss.baseLogDir=' +
                               base_log_dir + '\n')

    if specific_log_dir is None:
        jvm_options_file.write('-Dcompss.specificLogDir=\n')
    else:
        jvm_options_file.write('-Dcompss.specificLogDir=' +
                               specific_log_dir + '\n')

    jvm_options_file.write('-Dcompss.appLogDir=/tmp/' + my_uuid + '/\n')

    # TOOLS JVM FLAGS
    if graph:
        jvm_options_file.write('-Dcompss.graph=true\n')
    else:
        jvm_options_file.write('-Dcompss.graph=false\n')

    if monitor is None:
        jvm_options_file.write('-Dcompss.monitor=0\n')
    else:
        jvm_options_file.write('-Dcompss.monitor=' + str(monitor) + '\n')

    if not trace or trace == 0:
        # Deactivated
        jvm_options_file.write('-Dcompss.tracing=0' + '\n')
    elif trace == 1:
        # Basic
        jvm_options_file.write('-Dcompss.tracing=1\n')
        basic = compss_home + DEFAULT_TRACING_PATH + 'extrae_basic.xml'
        os.environ['EXTRAE_CONFIG_FILE'] = basic
    elif trace == 2:
        # Advanced
        jvm_options_file.write('-Dcompss.tracing=2\n')
        advanced = compss_home + DEFAULT_TRACING_PATH + 'extrae_advanced.xml'
        os.environ['EXTRAE_CONFIG_FILE'] = advanced
    elif trace == "scorep":
        # ScoreP tracing
        jvm_options_file.write('-Dcompss.tracing=-1\n')
    elif trace == "arm-map":
        # ARM-MAP profiling
        jvm_options_file.write('-Dcompss.tracing=-2\n')
    elif trace == "arm-ddt":
        # ARM-DDT debuger
        jvm_options_file.write('-Dcompss.tracing=-3\n')
    else:
        # Any other case: deactivated
        jvm_options_file.write('-Dcompss.tracing=0' + '\n')

    if extrae_cfg is None:
        jvm_options_file.write('-Dcompss.extrae.file=null\n')
    else:
        jvm_options_file.write('-Dcompss.extrae.file=' + extrae_cfg + '\n')

    if comm == 'GAT':
        gat = '-Dcompss.comm=es.bsc.compss.gat.master.GATAdaptor'
        jvm_options_file.write(gat + '\n')
    else:
        nio = '-Dcompss.comm=es.bsc.compss.nio.master.NIOAdaptor'
        jvm_options_file.write(nio + '\n')

    # INTERNAL COMPONENTS JVM FLAGS
    jvm_options_file.write('-Dcompss.conn=' + conn + '\n')
    jvm_options_file.write('-Dcompss.masterName=' + master_name + '\n')
    jvm_options_file.write('-Dcompss.masterPort=' + master_port + '\n')
    jvm_options_file.write('-Dcompss.scheduler=' + scheduler + '\n')
    jvm_options_file.write('-Dgat.adaptor.path=' + compss_home +
                           '/Dependencies/JAVA_GAT/lib/adaptors\n')
    if debug:
        jvm_options_file.write('-Dgat.debug=true\n')
    else:
        jvm_options_file.write('-Dgat.debug=false\n')
    jvm_options_file.write('-Dgat.broker.adaptor=sshtrilead\n')
    jvm_options_file.write('-Dgat.file.adaptor=sshtrilead\n')
    jvm_options_file.write('-Dcompss.worker.cp=' + cp + ':' +
                           compss_home + '/Runtime/compss-engine.jar:' +
                           classpath + '\n')
    jvm_options_file.write('-Dcompss.worker.jvm_opts=' +
                           jvm_workers + '\n')
    jvm_options_file.write('-Dcompss.worker.cpu_affinity=' +
                           cpu_affinity + '\n')
    jvm_options_file.write('-Dcompss.worker.gpu_affinity=' +
                           gpu_affinity + '\n')
    jvm_options_file.write('-Dcompss.worker.fpga_affinity=' +
                           fpga_affinity + '\n')
    jvm_options_file.write('-Dcompss.worker.fpga_reprogram=' +
                           fpga_reprogram + '\n')
    jvm_options_file.write('-Dcompss.profile.input=' +
                           profile_input + '\n')
    jvm_options_file.write('-Dcompss.profile.output=' +
                           profile_output + '\n')
    jvm_options_file.write('-Dcompss.scheduler.config=' +
                           scheduler_config + '\n')
    jvm_options_file.write('-Dcompss.external.adaptation=' +
                           external_adaptation + '\n')

    # SPECIFIC JVM OPTIONS FOR PYTHON
    jvm_options_file.write('-Djava.class.path=' + cp + ':' +
                           compss_home + '/Runtime/compss-engine.jar:' +
                           classpath + '\n')
    jvm_options_file.write('-Djava.library.path=' +
                           ld_library_path + '\n')
    jvm_options_file.write('-Dcompss.worker.pythonpath=' + cp + ':' +
                           pythonpath + '\n')
    jvm_options_file.write('-Dcompss.python.interpreter=' +
                           python_interpreter + '\n')
    jvm_options_file.write('-Dcompss.python.version=' +
                           python_version + '\n')
    jvm_options_file.write('-Dcompss.python.virtualenvironment=' +
                           python_virtual_environment + '\n')
    virtualenv_prefix = '-Dcompss.python.propagate_virtualenvironment='
    if propagate_virtual_environment:
        jvm_options_file.write(virtualenv_prefix + 'true\n')
    else:
        jvm_options_file.write(virtualenv_prefix + 'false\n')
    if mpi_worker:
        jvm_options_file.write('-Dcompss.python.mpi_worker=true\n')
    else:
        jvm_options_file.write('-Dcompss.python.mpi_worker=false\n')

    # Uncomment for debugging purposes
    # jvm_options_file.write('-Xcheck:jni\n')
    # jvm_options_file.write('-verbose:jni\n')

    # Close the file
    jvm_options_file.close()
    os.close(fd)
    os.environ['JVM_OPTIONS_FILE'] = temp_path

    # print("Uncomment if you want to check the configuration file path.")
    # print("JVM_OPTIONS_FILE: %s" % temp_path)


def setup_logger(debug, log_level, major_version, compss_home, log_path):
    """
    Initialize the logging facility.

    :param debug: Debug boolean
    :param log_level: Log level
    :param major_version: Python version
    :param compss_home: COMPSs installation folder
    :param log_path: Log destination path
    :return: Initialized logger
    """
    # Logging setup
    if debug or log_level == "debug":
        json_path = '/Bindings/python/' + \
                    major_version + \
                    '/log/logging_debug.json'
        init_logging(compss_home + json_path, log_path)
    elif log_level == "info":
        json_path = '/Bindings/python/' + \
                    major_version + \
                    '/log/logging_off.json'
        init_logging(compss_home + json_path, log_path)
    elif log_level == "off":
        json_path = '/Bindings/python/' + \
                    major_version + \
                    '/log/logging_off.json'
        init_logging(compss_home + json_path, log_path)
    else:
        # Default
        json_path = '/Bindings/python/' + \
                    str(major_version) + \
                    '/log/logging.json'
        init_logging(compss_home + json_path, log_path)
    return logging.getLogger("pycompss.runtime.launch")
