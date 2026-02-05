#!/bin/bash
set -e

echo "🚀 Iniciando POC..."

# Função para detectar sistema operacional
detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        echo "$ID"
    elif [ -f /etc/debian_version ]; then
        echo "debian"
    elif [ -f /etc/redhat-release ]; then
        echo "rhel"
    else
        echo "unknown"
    fi
}

# Função para instalar pacote (Ubuntu/Debian)
install_package_debian() {
    local package=$1
    local name=$2
    
    if ! dpkg -l | grep -q "^ii.*$package"; then
        echo "📦 Instalando $name..."
        if command -v sudo > /dev/null 2>&1; then
            sudo apt-get update -qq
            sudo apt-get install -y "$package" || {
                echo "⚠️  Falha ao instalar $name. Instale manualmente: sudo apt-get install $package"
                return 1
            }
            echo "✅ $name instalado com sucesso"
        else
            echo "⚠️  sudo não encontrado. Instale $name manualmente: apt-get install $package"
            return 1
        fi
    else
        echo "✅ $name já está instalado"
    fi
}

# Verificar se Docker está rodando
if ! docker info > /dev/null 2>&1; then
    echo "❌ Erro: Docker não está rodando!"
    exit 1
fi
echo "✅ Docker está rodando"

# Verificar qual comando docker-compose usar
if docker compose version > /dev/null 2>&1; then
    DOCKER_COMPOSE_CMD="docker compose"
    echo "✅ Usando Docker Compose plugin (docker compose)"
elif command -v docker-compose > /dev/null 2>&1; then
    DOCKER_COMPOSE_CMD="docker-compose"
    echo "✅ Usando Docker Compose standalone (docker-compose)"
else
    echo "❌ Erro: Docker Compose não encontrado!"
    echo "   Instale Docker Compose ou use Docker com plugin Compose"
    exit 1
fi

# Verificar e instalar libvirt se necessário
OS=$(detect_os)
if ! command -v virsh > /dev/null 2>&1; then
    echo "⚠️  libvirt não encontrado"
    if [ "$OS" = "ubuntu" ] || [ "$OS" = "debian" ]; then
        install_package_debian "libvirt-daemon-system" "libvirt"
        install_package_debian "libvirt-clients" "libvirt-clients"
        echo "ℹ️  Nota: Você pode precisar adicionar seu usuário ao grupo libvirt:"
        echo "   sudo usermod -aG libvirt \$USER"
        echo "   (faça logout e login novamente para aplicar)"
    else
        echo "⚠️  Sistema operacional não suportado para instalação automática"
        echo "   Instale libvirt manualmente para seu sistema"
    fi
else
    echo "✅ libvirt encontrado"
fi

# Verificar e instalar Maven se necessário
if ! command -v mvn > /dev/null 2>&1; then
    echo "⚠️  Maven não encontrado"
    if [ "$OS" = "ubuntu" ] || [ "$OS" = "debian" ]; then
        install_package_debian "maven" "Maven"
    elif [ "$OS" = "rhel" ] || [ "$OS" = "centos" ] || [ "$OS" = "fedora" ]; then
        echo "📦 Instalando Maven..."
        if command -v sudo > /dev/null 2>&1; then
            sudo yum install -y maven || sudo dnf install -y maven || {
                echo "⚠️  Falha ao instalar Maven. Instale manualmente"
                exit 1
            }
            echo "✅ Maven instalado com sucesso"
        else
            echo "⚠️  sudo não encontrado. Instale Maven manualmente"
            exit 1
        fi
    else
        echo "❌ Sistema operacional não suportado para instalação automática"
        echo "   Instale Maven manualmente:"
        echo "   Ubuntu/Debian: sudo apt-get install maven"
        echo "   CentOS/RHEL:   sudo yum install maven"
        echo "   macOS:         brew install maven"
        exit 1
    fi
else
    echo "✅ Maven encontrado"
fi

# Subir infraestrutura (Prometheus + Grafana)
echo "📦 Iniciando Prometheus e Grafana..."
$DOCKER_COMPOSE_CMD -f docker-compose-prometheus.yml up -d

# Aguardar Prometheus estar pronto
echo "⏳ Aguardando Prometheus estar pronto (15s)..."
sleep 15

# Verificar se Prometheus está respondendo
if command -v curl > /dev/null 2>&1; then
    if curl -s http://localhost:9090/-/healthy > /dev/null 2>&1; then
        echo "✅ Prometheus está respondendo"
    else
        echo "⚠️  Aviso: Prometheus pode não estar pronto ainda"
    fi
    
    # Verificar PushGateway
    if curl -s http://localhost:9091/-/healthy > /dev/null 2>&1; then
        echo "✅ PushGateway está respondendo"
    else
        echo "⚠️  Aviso: PushGateway pode não estar pronto ainda"
    fi
else
    echo "⚠️  Aviso: curl não encontrado, pulando verificação de saúde"
fi

# Compilar aplicação
echo "🔨 Compilando aplicação..."
mvn clean package

# Verificar se o JAR foi criado
JAR_FILE=$(find target -name "libvirt-collector-poc-*.jar" | head -n 1)
if [ -z "$JAR_FILE" ]; then
    echo "❌ Erro: JAR não encontrado após compilação!"
    echo "Verifique os erros de compilação acima."
    exit 1
fi

echo "✅ Compilação concluída: $JAR_FILE"

# Executar JAR
echo "▶️  Executando coletor..."
echo ""
echo "✅ POC rodando!"
echo ""
echo "📊 URLs de acesso:"
echo "   Prometheus:  http://localhost:9090"
echo "   PushGateway: http://localhost:9091"
echo "   Grafana:     http://localhost:3000 (admin/admin)"
echo ""
echo "Pressione Ctrl+C para parar"
echo ""

java -jar "$JAR_FILE"
