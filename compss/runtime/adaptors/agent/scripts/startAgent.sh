#!/bin/bash

#COMPSs Options
DEFAULT_DEBUG="off"
DEFAULT_COMM="es.bsc.compss.agent.comm.CommAgentAdaptor"
DEFAULT_SCHEDULER="es.bsc.compss.scheduler.loadbalancing.LoadBalancingScheduler"

#mF2C Options
DEFAULT_REPORT_ADDRESS="NO_REPORT"

#DataClay Options
DC_CLASSPATH="$(for i in /opt/COMPSs/storage/lib/*.jar ; do echo -n ${i}: ; done)/opt/COMPSs/storage/dataclay.jar"
DC_TOOL="java -Dorg.apache.logging.log4j.simplelog.StatusLogger.level=OFF -cp ${DC_CLASSPATH}"

DEFAULT_DC_LOGICMODULE_HOST="127.0.0.1"
DEFAULT_DC_LOGICMODULE_PORT="11034"
DEFAULT_DC_USERNAME="AppUser"
DEFAULT_DC_PASSWORD="AppPwd"
DEFAULT_DC_DATASET="AppDS"
DEFAULT_DC_NAMESPACE="AppNS"

usage() {
cat << EOF
Usage: $0 [OPTION]...

Mandatory options:
  -h, --hostname            name of the mF2C hostname

  -a, --app                 application jar


COMPSs options:
  -rp, --rest_port          port on which the agent sets up a REST interface. (<=0: Disabled)

  -cp, --comm_port          port on which the agent sets up a Comm interface. (<=0: Disabled)

  --comm=<ClassName>        Class that implements the adaptor for communications
                            Supported adaptors: es.bsc.compss.nio.master.NIOAdaptor | es.bsc.compss.gat.master.GATAdaptor |es.bsc.compss.agent.comm.CommAgentAdaptor | es.bsc.compss.agent.rest.master.Adaptor
                            Default: es.bsc.compss.agent.comm.CommAgentAdaptor

  --scheduler=<className>   Class that implements the Scheduler for COMPSs
                            Supported schedulers: es.bsc.compss.scheduler.fullGraphScheduler.FullGraphScheduler
                                                | es.bsc.compss.scheduler.fifoScheduler.FIFOScheduler
                                                | es.bsc.compss.scheduler.resourceEmptyScheduler.ResourceEmptyScheduler
                            Default: es.bsc.compss.scheduler.loadbalancing.LoadBalancingScheduler

  -d, --debug               Set debug level. (Default: disabled)

  -log, --log_dir           log directory. (Default: /tmp/${app_uuid})

  --project                  path of the project file 
                            (Default: ${COMPSS_HOME}/Runtime/configuration/xml/projects/examples/local/project.xml)

  --resources                path of the resources file 
                            (Default: ${COMPSS_HOME}/Runtime/configuration/xml/resources/examples/local/resources.xml)
mF2C options:
  -r, --report_address      endpoint where to report app execution updates


DataClay options:
  -DC, --no-dataclay        Disable DataClay  

  -lm, --logicmodule        DataClay's logic module endpoint

  -u, --username            DataClay user

  -pwd, --password          DataClay password 

  -ds, --dataset            DataClay dataset name

  -ns, --namespace          DataClay namespace


Other options:
  --help                    prints this message

EOF
}

#'


parse_options() {
  DC_ENABLED=true

  while true; do
    case "$1" in

# MANDATORY OPTIONS
      -h    | --hostname )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        AGENT_HOSTNAME=$2;
        shift 2;;

      -a    | --app )   
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        APPLICATION_PATH=$2;
        shift 2;;

# AGENT OPTIONS
      -rp     | --rest_port ) 
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi    
	export REST_AGENT_PORT=$2;
        shift 2;;

      -cp     | --comm_port ) 
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi    
        export COMM_AGENT_PORT=$2;
        shift 2;;

      --comm )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        COMM=$2;
        shift 2;;
      --scheduler )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        SCHEDULER=$2;
        shift 2;;
      -d    | --debug )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        DEBUG=$2;
        shift 2;;

      -log | --log_dir )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        LOG_DIR=$2;
        shift 2;;
      
# mF2C OPTIONS
      -r    | --report_address )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        REPORT_ADDRESS=$2;
        shift 2;;

# DATACLAY OPTIONS
      -DC | --no-dataclay )
        DC_ENABLED=false
        shift 1;;

      -lm    | --logicmodule )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        OLD_IFS=${IFS}
        IFS=':' read -ra ADDR <<< "$2"
        if [ "${ADDR[0]}" != "" ]; then
          DC_LOGICMODULE_HOST="${ADDR[0]}"
        fi
        if [ "${ADDR[1]}" != "" ]; then
          DC_LOGICMODULE_PORT="${ADDR[1]}"
        fi
        IFS=${OLD_IFS}
        shift 2;;

      -u     | --user ) 
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        DC_USERNAME=$2;
        shift 2;;

      -pwd     | --password )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        DC_PASSWORD=$2;
        shift 2;;

      -ds     | --dataset )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        DC_DATASET=$2;
        shift 2;;

      -ns    | --namespace )
        if [ "$#" -lt 2 ]; then
          echo "Illegal number of params"
          usage
          exit
        fi
        DC_NAMESPACE=$2;
        shift 2;;

      --help)    
        usage;
        exit;;                    
      -- ) 
        shift; 
        break;;
      * ) 
        shift; 
        break ;;
    esac
  done

  if [[ -z "${AGENT_HOSTNAME}" ]]; then
    echo "ERROR! HOSTNAME not set"
    usage
    exit
  fi
    if [[ -z "${APPLICATION_PATH}" ]]; then
      echo "ERROR! APPLICATION_PATH not set"
      usage
      exit
  fi

  if [[ -z "${COMM}" ]]; then
    comm=${DEFAULT_COMM}
  fi
  if  [[ "${comm}" == "es.bsc.compss.agent.comm.CommAgentAdaptor" ]]; then
    if [[ -z "${COMM_AGENT_PORT}" ]]; then
	      echo "When using the Comm Agent adaptor, COMM port needs to be specified (-cp/--comm_port)"
        exit 1
    fi
    comm="${comm} -Dcompss.masterPort=${COMM_AGENT_PORT}"
  fi

  if [[ -z "${AGENT_PORT}" ]]; then
    AGENT_PORT="${DEFAULT_AGENT_PORT}"
  fi
  if [[ -z "${DEBUG}" ]]; then
    DEBUG="${DEFAULT_DEBUG}"
  fi

  # Setting up values for mF2C options
  if [[ -z "${REPORT_ADDRESS}" ]]; then
    REPORT_ADDRESS="${DEFAULT_REPORT_ADDRESS}"
  fi
  if [[ -n "${REPORT_ADDRESS}" ]] && [[ "${REPORT_ADDRESS}" != "NO_REPORT" ]] ; then
    REPORT_ADDRESS_CONFIG="-Dreport.address=${REPORT_ADDRESS} "
  fi

  if [ "$DC_ENABLED" = true ] ; then
    if [[ -z "${DC_LOGICMODULE_HOST}" ]]; then
      DC_LOGICMODULE_HOST="${DEFAULT_DC_LOGICMODULE_HOST}"
    fi
    if [[ -z "${DC_LOGICMODULE_PORT}" ]]; then
      DC_LOGICMODULE_PORT="${DEFAULT_DC_LOGICMODULE_PORT}"
    fi
    if [[ -z "${DC_USERNAME}" ]]; then
      DC_USERNAME="${DEFAULT_DC_USERNAME}"
    fi
    if [[ -z "${DC_PASSWORD}" ]]; then
      DC_PASSWORD="${DEFAULT_DC_PASSWORD}"
    fi
    if [[ -z "${DC_DATASET}" ]]; then
      DC_DATASET="${DEFAULT_DC_DATASET}"
    fi
    if [[ -z "${DC_NAMESPACE}" ]]; then
      DC_NAMESPACE="${DEFAULT_DC_NAMESPACE}"
    fi
  fi
  echo  "AGENT_HOSTNAME: ${AGENT_HOSTNAME}"
  echo  "REST_AGENT_PORT: ${REST_AGENT_PORT}"
  echo  "COMM_AGENT_PORT: ${COMM_AGENT_PORT}"
  echo  "DEBUG: ${DEBUG}"
  echo  "REPORT_ADDRESS: ${REPORT_ADDRESS}"
  if [ "$DC_ENABLED" = true ] ; then
    echo  "DC_LOGICMODULE_HOST: ${DC_LOGICMODULE_HOST}"
    echo  "DC_LOGICMODULE_PORT: ${DC_LOGICMODULE_PORT}"
    echo  "DC_USERNAME: ${DC_USERNAME}"
    echo  "DC_PASSWORD: ${DC_PASSWORD}"
    echo  "DC_DATASET: ${DC_DATASET}"
    echo  "DC_NAMESPACE: ${DC_NAMESPACE}"
  fi
}

generate_client_properties() {
  echo "    * Creating client.properties at ${CURRENT_DIR}"
  mkdir -p "${CURRENT_DIR}/cfgfiles"
  cat << EOF >> ${CURRENT_DIR}/cfgfiles/client.properties
HOST=${DC_LOGICMODULE_HOST}
TCPPORT=${DC_LOGICMODULE_PORT}
EOF
}

generate_session_properties() {
  echo "    * Creating session.properties at ${CURRENT_DIR}"
  cat << EOF >> ${CURRENT_DIR}/cfgfiles/session.properties
Account=${DC_USERNAME}
Password=${DC_PASSWORD}
StubsClasspath=${CURRENT_DIR}/stubs
DataSets=${DC_DATASET}
DataSetForStore=${DC_DATASET}
DataClayClientConfig=${CURRENT_DIR}/cfgfiles/client.properties
EOF
}

generate_dataclay_stubs() {
  # Registering User
  ${DC_TOOL} dataclay.tool.NewAccount ${DC_USERNAME} ${DC_PASSWORD}
  #Registering DATASET
  ${DC_TOOL} dataclay.tool.NewDataContract ${DC_USERNAME} ${DC_PASSWORD} ${DC_DATASET} ${DC_USERNAME}

  #Registering Application classes
  ${DC_TOOL} dataclay.tool.NewNamespace ${DC_USERNAME} ${DC_PASSWORD} ${DC_NAMESPACE} java

  mkdir classes
  cd classes
  jar xf "${APPLICATION_PATH}"
  ls | grep -v datamodel | xargs rm -rf
  cd ..

  ${DC_TOOL} dataclay.tool.NewModel ${DC_USERNAME} ${DC_PASSWORD} ${DC_NAMESPACE} classes
  rm -rf classes

  #Obtaining stubs
  ${DC_TOOL} dataclay.tool.AccessNamespace ${DC_USERNAME} ${DC_PASSWORD} ${DC_NAMESPACE}
  ${DC_TOOL} dataclay.tool.GetStubs ${DC_USERNAME} ${DC_PASSWORD} ${DC_NAMESPACE} "${CURRENT_DIR}/stubs"
}

##############################################
############### MAIN CODE ####################
##############################################

# Setting up default values
SCHEDULER=${DEFAULT_SCHEDULER}

# Obtain COMPSs installation root
if [ -z "${COMPSS_HOME}" ]; then
  COMPSS_HOME="$( cd "$( dirname "${BASH_SOURCE[0]}" )"/../../../../../ && pwd )"
fi
export COMPSS_HOME=${COMPSS_HOME}
echo "Using COMPSs installation on ${COMPSS_HOME}"

parse_options "$@"

uuid=$(uuidgen)
if [ -z "$uuid" ]; then
  uuid=$(cat /proc/sys/kernel/random/uuid)
fi

CURRENT_DIR=/tmp/${uuid}
rm -rf "${CURRENT_DIR}"
mkdir -p "${CURRENT_DIR}"
cd "${CURRENT_DIR}"


if [ -z "${LOG_DIR}" ]; then
  LOG_DIR="/tmp/${uuid}"
fi


# Loading all necessary jars on classpath
if [ -f "${APPLICATION_PATH}" ]; then
  CLASSPATH="${APPLICATION_PATH}"
  APPLICATION_FOLDER="$( cd "$( dirname "${APPLICATION_PATH}" )" && pwd )"
  CLASSPATH="${CLASSPATH}:${APPLICATION_FOLDER}/lib/*"
fi

CLASSPATH="${CLASSPATH}:${COMPSS_HOME}/Runtime/adaptors/CommAgent/worker/compss-adaptors-agent-comm-worker.jar"
#CLASSPATH="${CLASSPATH}:${COMPSS_HOME}/Runtime/adaptors/Commagent/master/compss-adaptors-agent-comm-master.jar"

if [ "$DC_ENABLED" = true ] ; then
  echo "GENERATING DATACLAY CONFIGURATION FILES..."
  generate_client_properties
  generate_session_properties

  if [ -f "${APPLICATION_PATH}" ]; then
    echo "Preparing DataClay environment"
    generate_dataclay_stubs
    CLASSPATH="${CURRENT_DIR}/stubs:${DC_CLASSPATH}:${CLASSPATH}"
  fi
fi

if [ ! -z "${COMM_AGENT_PORT}" ]; then
  comm_port_msg="(comm: ${COMM_AGENT_PORT})"
fi
if [ ! -z "${REST_AGENT_PORT}" ]; then
  rest_port_msg="(rest: ${REST_AGENT_PORT})"
fi
echo "Launching COMPSs agent on Worker ${AGENT_HOSTNAME} and ports ${comm_port_msg} ${rest_port_msg} with debug level ${DEBUG}"

if [ "$DC_ENABLED" = true ] ; then
  echo "User authenticates to Dataclay with username ${DC_USERNAME} and password ${DC_PASSWORD}"
  echo "DataClay will use the ${DC_DATASET} dataset and the namespace ${DC_NAMESPACE}"
  DATACLAY_CONFIG_OPT="-Ddataclay.configpath=${CURRENT_DIR}/cfgfiles/session.properties " 
fi
echo "------------------------"
echo "HOSTNAME: ${AGENT_HOSTNAME}"
echo "------------------------"


echo java \
-cp "${CLASSPATH}" \
-Dcompss.masterName="${AGENT_HOSTNAME}" \
-Dcompss.uuid="${uuid}" \
-Dcompss.appLogDir="${LOG_DIR}" \
-Dcompss.comm=${comm} \
-Dcompss.agent.configpath="${COMPSS_HOME}/Runtime/configuration/agents/all.json" \
-Dcompss.project.schema="${COMPSS_HOME}/Runtime/configuration/xml/projects/project_schema.xsd" \
-Dcompss.resources.schema="${COMPSS_HOME}/Runtime/configuration/xml/resources/resources_schema.xsd" \
-Dlog4j.configurationFile="${COMPSS_HOME}/Runtime/configuration/log/COMPSsMaster-log4j.${DEBUG}" \
-Dcompss.scheduler=${SCHEDULER} \
${DATACLAY_CONFIG_OPT} \
es.bsc.compss.agent.Agent


java \
-cp "${CLASSPATH}" \
-Dcompss.masterName="${AGENT_HOSTNAME}" \
-Dcompss.uuid="${uuid}" \
-Dcompss.appLogDir="${LOG_DIR}" \
-Dcompss.specificLogDir="${LOG_DIR}" \
-Dcompss.comm=${comm} \
-Dcompss.agent.configpath="${COMPSS_HOME}/Runtime/configuration/agents/all.json" \
-Dcompss.project.schema="${COMPSS_HOME}/Runtime/configuration/xml/projects/project_schema.xsd" \
-Dcompss.resources.schema="${COMPSS_HOME}/Runtime/configuration/xml/resources/resources_schema.xsd" \
-Dlog4j.configurationFile="${COMPSS_HOME}/Runtime/configuration/log/COMPSsMaster-log4j.${DEBUG}" \
-Dcompss.scheduler=${SCHEDULER} \
${DATACLAY_CONFIG_OPT} \
${REPORT_ADDRESS_CONFIG}\
es.bsc.compss.agent.Agent


