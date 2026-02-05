#!/bin/bash

# Script completo de demonstração - Criar, modificar e gerenciar VMs

set -e

# Cores
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

# Função para criar VM
criar_vm() {
    local VM_NAME=$1
    local MEMORY_KB=${2:-2097152}  # Padrão: 2GB
    local VCPU=${3:-2}              # Padrão: 2 vCPUs
    
    echo -e "${BLUE}📝 Criando VM: $VM_NAME${NC}"
    echo "   Memória: $((MEMORY_KB / 1024)) MB"
    echo "   vCPUs: $VCPU"
    
    # Remover se existir
    virsh destroy "$VM_NAME" 2>/dev/null || true
    virsh undefine "$VM_NAME" 2>/dev/null || true
    sleep 1
    
    # Criar XML
    XML_FILE="/tmp/${VM_NAME}.xml"
    cat > "$XML_FILE" <<VMXML
<domain type='qemu'>
  <name>${VM_NAME}</name>
  <memory unit='KiB'>${MEMORY_KB}</memory>
  <currentMemory unit='KiB'>${MEMORY_KB}</currentMemory>
  <vcpu placement='static'>${VCPU}</vcpu>
  <os>
    <type arch='x86_64'>hvm</type>
  </os>
  <features>
    <acpi/>
  </features>
  <clock offset='utc'/>
  <on_poweroff>destroy</on_poweroff>
  <on_reboot>restart</on_reboot>
  <on_crash>destroy</on_crash>
  <devices>
    <console type='pty'>
      <target type='serial' port='0'/>
    </console>
  </devices>
</domain>
VMXML
    
    if virsh define "$XML_FILE" 2>&1; then
        rm -f "$XML_FILE"
        echo -e "${GREEN}   ✅ VM definida${NC}"
        
        if virsh start "$VM_NAME" 2>&1; then
            sleep 2
            STATE=$(virsh dominfo "$VM_NAME" 2>/dev/null | grep "State:" | awk '{print $2}' || echo "unknown")
            if [ "$STATE" = "running" ]; then
                echo -e "${GREEN}   ✅ VM iniciada e rodando${NC}"
            else
                echo -e "${YELLOW}   ⚠️  VM definida (State: $STATE)${NC}"
            fi
            return 0
        else
            echo -e "${YELLOW}   ⚠️  VM definida mas não iniciou${NC}"
            return 0
        fi
    else
        echo -e "${RED}   ❌ Erro ao criar VM${NC}"
        rm -f "$XML_FILE"
        return 1
    fi
}

# Função para deletar VM
deletar_vm() {
    local VM_NAME=$1
    
    echo -e "${YELLOW}🗑️  Deletando VM: $VM_NAME${NC}"
    
    virsh destroy "$VM_NAME" 2>/dev/null && echo "   ✅ VM parada" || echo "   VM já estava parada"
    virsh undefine "$VM_NAME" 2>/dev/null && echo -e "${GREEN}   ✅ VM deletada${NC}" || echo -e "${RED}   ❌ Erro ao deletar${NC}"
}

# Função para aumentar memória
aumentar_memoria() {
    local VM_NAME=$1
    local NOVA_MEMORIA_KB=$2
    
    echo -e "${BLUE}📈 Aumentando memória de $VM_NAME para $((NOVA_MEMORIA_KB / 1024)) MB${NC}"
    
    # Parar VM se estiver rodando
    virsh destroy "$VM_NAME" 2>/dev/null || true
    
    # Obter XML atual
    virsh dumpxml "$VM_NAME" > /tmp/${VM_NAME}_backup.xml 2>/dev/null || {
        echo -e "${RED}   ❌ VM não encontrada${NC}"
        return 1
    }
    
    # Modificar memória no XML
    sed -i "s/<memory unit='KiB'>[0-9]*<\/memory>/<memory unit='KiB'>${NOVA_MEMORIA_KB}<\/memory>/" /tmp/${VM_NAME}_backup.xml
    sed -i "s/<currentMemory unit='KiB'>[0-9]*<\/currentMemory>/<currentMemory unit='KiB'>${NOVA_MEMORIA_KB}<\/currentMemory>/" /tmp/${VM_NAME}_backup.xml
    
    # Redefinir VM
    virsh undefine "$VM_NAME" 2>/dev/null || true
    if virsh define /tmp/${VM_NAME}_backup.xml 2>&1; then
        rm -f /tmp/${VM_NAME}_backup.xml
        echo -e "${GREEN}   ✅ Memória aumentada${NC}"
        
        # Reiniciar se estava rodando
        virsh start "$VM_NAME" 2>&1 && echo "   ✅ VM reiniciada" || true
        return 0
    else
        echo -e "${RED}   ❌ Erro ao modificar memória${NC}"
        return 1
    fi
}

# Função para aumentar vCPUs
aumentar_vcpus() {
    local VM_NAME=$1
    local NOVO_VCPU=$2
    
    echo -e "${BLUE}📈 Aumentando vCPUs de $VM_NAME para $NOVO_VCPU${NC}"
    
    # Parar VM se estiver rodando
    virsh destroy "$VM_NAME" 2>/dev/null || true
    
    # Obter XML atual
    virsh dumpxml "$VM_NAME" > /tmp/${VM_NAME}_backup.xml 2>/dev/null || {
        echo -e "${RED}   ❌ VM não encontrada${NC}"
        return 1
    }
    
    # Modificar vCPU no XML
    sed -i "s/<vcpu[^>]*>[0-9]*<\/vcpu>/<vcpu placement='static'>${NOVO_VCPU}<\/vcpu>/" /tmp/${VM_NAME}_backup.xml
    
    # Redefinir VM
    virsh undefine "$VM_NAME" 2>/dev/null || true
    if virsh define /tmp/${VM_NAME}_backup.xml 2>&1; then
        rm -f /tmp/${VM_NAME}_backup.xml
        echo -e "${GREEN}   ✅ vCPUs aumentados${NC}"
        
        # Reiniciar se estava rodando
        virsh start "$VM_NAME" 2>&1 && echo "   ✅ VM reiniciada" || true
        return 0
    else
        echo -e "${RED}   ❌ Erro ao modificar vCPUs${NC}"
        return 1
    fi
}

# Função para mostrar status
mostrar_status() {
    echo ""
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    echo -e "${BLUE}📊 STATUS DAS VMs${NC}"
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    echo ""
    
    echo "VMs em execução:"
    virsh list 2>/dev/null | head -20 || echo "   Nenhuma VM rodando"
    
    echo ""
    echo "Todas as VMs (incluindo paradas):"
    virsh list --all 2>/dev/null | head -20 || echo "   Nenhuma VM encontrada"
    
    echo ""
    echo "Estatísticas poc-vm-*:"
    RUNNING=$(virsh list --name 2>/dev/null | grep -c "^poc-vm-" 2>/dev/null || echo "0")
    DEFINED=$(virsh list --all --name 2>/dev/null | grep -c "^poc-vm-" 2>/dev/null || echo "0")
    echo "   Definidas: $DEFINED"
    echo "   Rodando: $RUNNING"
    echo ""
}

# Função para mostrar informações detalhadas de uma VM
info_vm() {
    local VM_NAME=$1
    
    echo ""
    echo -e "${BLUE}📋 Informações da VM: $VM_NAME${NC}"
    echo ""
    
    if virsh dominfo "$VM_NAME" 2>/dev/null; then
        echo ""
        echo "Recursos:"
        virsh dominfo "$VM_NAME" 2>/dev/null | grep -E "Max memory|CPU\(s\)" || true
    else
        echo -e "${RED}   ❌ VM não encontrada${NC}"
    fi
    echo ""
}

# Menu interativo
menu() {
    clear
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo -e "${GREEN}   DEMONSTRAÇÃO POC - Gerenciamento de VMs${NC}"
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo ""
    echo "1. Criar VM"
    echo "2. Criar múltiplas VMs"
    echo "3. Deletar VM"
    echo "4. Aumentar memória de VM"
    echo "5. Aumentar vCPUs de VM"
    echo "6. Parar VM"
    echo "7. Iniciar VM"
    echo "8. Mostrar status"
    echo "9. Informações detalhadas de VM"
    echo "10. Cenário de demonstração completo"
    echo "0. Sair"
    echo ""
    read -p "Escolha uma opção: " opcao
    
    case $opcao in
        1)
            read -p "Nome da VM: " nome
            read -p "Memória em MB (padrão 2048): " mem
            read -p "vCPUs (padrão 2): " vcpu
            mem=${mem:-2048}
            vcpu=${vcpu:-2}
            criar_vm "$nome" $((mem * 1024)) "$vcpu"
            pause
            ;;
        2)
            read -p "Quantas VMs criar: " num
            for i in $(seq 1 $num); do
                criar_vm "poc-vm-$i"
            done
            pause
            ;;
        3)
            mostrar_status
            read -p "Nome da VM para deletar: " nome
            deletar_vm "$nome"
            pause
            ;;
        4)
            mostrar_status
            read -p "Nome da VM: " nome
            read -p "Nova memória em MB: " mem
            aumentar_memoria "$nome" $((mem * 1024))
            pause
            ;;
        5)
            mostrar_status
            read -p "Nome da VM: " nome
            read -p "Novo número de vCPUs: " vcpu
            aumentar_vcpus "$nome" "$vcpu"
            pause
            ;;
        6)
            mostrar_status
            read -p "Nome da VM para parar: " nome
            virsh destroy "$nome" 2>&1 && echo -e "${GREEN}✅ VM parada${NC}" || echo -e "${RED}❌ Erro${NC}"
            pause
            ;;
        7)
            mostrar_status
            read -p "Nome da VM para iniciar: " nome
            virsh start "$nome" 2>&1 && echo -e "${GREEN}✅ VM iniciada${NC}" || echo -e "${RED}❌ Erro${NC}"
            pause
            ;;
        8)
            mostrar_status
            pause
            ;;
        9)
            mostrar_status
            read -p "Nome da VM: " nome
            info_vm "$nome"
            pause
            ;;
        10)
            cenario_demo
            ;;
        0)
            echo "Saindo..."
            exit 0
            ;;
        *)
            echo "Opção inválida"
            sleep 1
            ;;
    esac
}

# Função para pausar
pause() {
    echo ""
    read -p "Pressione ENTER para continuar..."
}

# Cenário de demonstração completo
cenario_demo() {
    clear
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo -e "${GREEN}   CENÁRIO DE DEMONSTRAÇÃO COMPLETO${NC}"
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo ""
    echo "Este cenário vai:"
    echo "1. Limpar VMs existentes"
    echo "2. Criar 3 VMs inicialmente"
    echo "3. Mostrar métricas"
    echo "4. Escalar para 5 VMs"
    echo "5. Aumentar recursos de uma VM"
    echo "6. Reduzir para 3 VMs"
    echo ""
    read -p "Iniciar demonstração? (s/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Ss]$ ]]; then
        return
    fi
    
    # Limpar VMs existentes
    echo ""
    echo -e "${YELLOW}🧹 Limpando VMs existentes...${NC}"
    virsh list --all --name 2>/dev/null | grep "^poc-vm-" | while read vm; do
        virsh destroy "$vm" 2>/dev/null || true
        virsh undefine "$vm" 2>/dev/null || true
    done
    sleep 2
    
    # Etapa 1: Criar 3 VMs
    echo ""
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    echo -e "${BLUE}ETAPA 1: Criando 3 VMs${NC}"
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    for i in {1..3}; do
        criar_vm "poc-vm-$i" 2097152 2
    done
    mostrar_status
    echo ""
    echo -e "${GREEN}✅ 3 VMs criadas${NC}"
    echo "📊 Verifique no Prometheus: count(vm_cpu_time_seconds)"
    pause
    
    # Etapa 2: Escalar para 5 VMs
    echo ""
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    echo -e "${BLUE}ETAPA 2: Escalando para 5 VMs${NC}"
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    for i in {4..5}; do
        criar_vm "poc-vm-$i" 2097152 2
    done
    mostrar_status
    echo ""
    echo -e "${GREEN}✅ Agora temos 5 VMs${NC}"
    echo "📊 Verifique no Prometheus: count(vm_cpu_time_seconds) = 5"
    pause
    
    # Etapa 3: Aumentar recursos de uma VM
    echo ""
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    echo -e "${BLUE}ETAPA 3: Aumentando recursos da poc-vm-1${NC}"
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    echo "Antes:"
    info_vm "poc-vm-1"
    aumentar_memoria "poc-vm-1" 4194304  # 4GB
    aumentar_vcpus "poc-vm-1" 4
    echo "Depois:"
    info_vm "poc-vm-1"
    echo -e "${GREEN}✅ Recursos aumentados${NC}"
    echo "📊 Métricas devem refletir mudanças em até 30 segundos"
    pause
    
    # Etapa 4: Reduzir para 3 VMs
    echo ""
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    echo -e "${BLUE}ETAPA 4: Reduzindo para 3 VMs${NC}"
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    for i in {4..5}; do
        deletar_vm "poc-vm-$i"
    done
    mostrar_status
    echo ""
    echo -e "${GREEN}✅ Reduzido para 3 VMs${NC}"
    echo "📊 Verifique no Prometheus: count(vm_cpu_time_seconds) = 3"
    pause
    
    # Resumo
    echo ""
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo -e "${GREEN}📊 DEMONSTRAÇÃO CONCLUÍDA${NC}"
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo ""
    echo "✅ Demonstrado:"
    echo "   - Criação de VMs"
    echo "   - Escalabilidade (3 → 5 → 3 VMs)"
    echo "   - Modificação de recursos (memória e vCPUs)"
    echo "   - Métricas em tempo real"
    echo ""
    echo "📊 URLs:"
    echo "   Prometheus:  http://localhost:9090"
    echo "   Grafana:     http://localhost:3000"
    echo ""
    pause
}

# Loop principal
if [ "$1" = "demo" ]; then
    cenario_demo
elif [ "$1" = "status" ]; then
    mostrar_status
elif [ "$1" = "criar" ]; then
    criar_vm "${2:-poc-vm-1}" ${3:-2097152} ${4:-2}
elif [ "$1" = "deletar" ]; then
    deletar_vm "${2:-poc-vm-1}"
elif [ "$1" = "memoria" ]; then
    aumentar_memoria "${2:-poc-vm-1}" ${3:-4194304}
elif [ "$1" = "vcpu" ]; then
    aumentar_vcpus "${2:-poc-vm-1}" ${3:-4}
else
    # Menu interativo
    while true; do
        menu
    done
fi
