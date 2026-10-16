# TurboDash

App Android para MegaSquirt (MS2/Extra, MS3 e variantes como a MegaSpeed) com painéis prontos,
alertas de segurança do motor, gravação de log e visualização dos mapas. **Versão 0.1: somente leitura**,
o app não grava nada na ECU.

## Estrutura

| Módulo | O que tem |
|---|---|
| `core/` | Kotlin puro, sem Android, com testes. Lê o INI do TunerStudio, a calibração `.msq`, o protocolo serial (envelope com CRC32), decodifica os canais em tempo real, gera alertas, grava e lê log `.msl`, analisa logs e inclui o simulador de ECU. |
| `app/` | App Android (Jetpack Compose) com conexão Bluetooth SPP e as telas Painel, Mapas, Logs e Ajustes. |

Tudo vem do INI: nenhum endereço de tabela fica fixo no código. Por isso o mesmo app funciona com outros firmwares.

## Compilar e testar

```
gradlew :core:test          # testes do núcleo (usam o INI e o .msq reais do Vectra)
gradlew :app:assembleDebug  # gera app/build/outputs/apk/debug/app-debug.apk
```

## Primeiro uso no celular

1. Instale o APK e abra **Ajustes**.
2. **Importar INI**: use `Documentos\TunerStudioProjects\<projeto>\projectCfg\mainController.ini`.
3. **Importar project.properties** (mesma pasta). Ele preenche as opções do projeto (CELSIUS, LAMBDA...) e o Bluetooth que o TunerStudio já usa.
4. Opcional: **Importar .msq**, que é usado pelo simulador e para ver os mapas sem conectar.
5. Escolha o módulo Bluetooth pareado (ou um simulador) e toque em **Conectar**.

Ao conectar, o app confere a assinatura da ECU com a do INI, lê a calibração inteira, começa a mostrar os
dados e, por padrão, já começa a gravar o log (`.msl`, abre no MegaLogViewer).

## Alertas (padrões)

| Alerta | Condição |
|---|---|
| Ar admitido | ≥ 55 °C atenção, ≥ 70 °C crítico |
| Água | ≥ 98 °C atenção, ≥ 105 °C crítico |
| Mistura pobre com carga | MAP ≥ 90 kPa, TPS > 5% e lambda > alvo + 0,05 (crítico) |
| Correção da sonda no limite | \|EGO − 100\| ≥ 15% |
| Duty dos bicos | ≥ 85% atenção, ≥ 90% crítico |
| Overboost | MAP ≥ 200 kPa |
| Bateria, perda de sincronismo | Tensão fora de 11,5 a 15,5 V; contador de perda de sincronismo aumentou |

Todos os limites podem ser editados em Ajustes.
