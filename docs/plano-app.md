# App Android para MegaSquirt (MS2/Extra MegaSpeed) — dashboard + edição de mapa

## Contexto
O TunerStudio (MS Lite) é datado e ruim no celular; o RealDash tem dash bonito mas é difícil de configurar e não edita mapa.
Objetivo: app Android próprio para o Vectra 16v Turbo (MS2/Extra "MegaSpeed 2 Xtreme", firmware BR) com
dashboards prontos, alertas de segurança (histórico de pistões quebrados por calor) e, em fase posterior, edição do mapa.
Por enquanto é só planejamento — nada será criado.

## Fato-chave descoberto
O INI do firmware já existe: `C:\Users\davi.ladeira\Documents\TunerStudioProjects\Vectra16vT\projectCfg\mainController.ini`
(382 KB, 5712 linhas, signature `"MS2Extra MegaSpeed "`). Ele descreve TUDO que o app precisa:
- Protocolo: `messageEnvelopeFormat = msEnvelope_1.0` (pacote com tamanho + CRC32), big-endian, 115200 baud
- Tempo real: `ochGetCommand = "r\$tsCanId\x07%2o%2c"`, `ochBlockSize = 219` bytes; canais em `[OutputChannels]`
- Mapa: 7 páginas de 1024 bytes; `pageReadCommand` (r), `pageChunkWrite` (w), `burnCommand` (b), `crc32CheckCommand` (k); blocos de até 256 bytes
- Tabelas/curvas/gauges/menus: seções `[Constants]`, `[TableEditor]`, `[CurveEditor]`, `[GaugeConfigurations]`, `[Datalog]`
=> Estratégia: o app **lê o INI** (como o TunerStudio faz) em vez de hardcodar offsets. Funciona com qualquer MS2/MS3 que tenha INI.

## Arquitetura proposta
- **Kotlin + Jetpack Compose** (nativo; melhor acesso a USB-serial e Bluetooth clássico)
- Camadas:
  1. `transport` — USB-OTG serial (lib usb-serial-for-android: FTDI/CH340/PL2303), Bluetooth SPP (HC-05/06), opcional Wi-Fi (ESP32 bridge)
  2. `protocol` — msEnvelope_1.0 (framing + CRC32), comandos Q/S, r/w/b/k, CAN ID
  3. `ini` — parser do formato INI do TunerStudio (constantes, escalas, bits, expressões de canais calculados, condições `#if`)
  4. `ecu` — polling de tempo real (alvo 15–20 Hz), cache das páginas, diff local × ECU
  5. `ui` — dashboards, editor de tabelas, logs, alertas
  6. `storage` — .msq (XML, compatível com TunerStudio) e .msl (compatível com MegaLogViewer)
- **Simulador/replay**: ECU fake que reproduz um .msl — permite desenvolver sem o carro.

## Fases
1. **MVP somente leitura** — conectar, validar signature, ler canais, 3–4 dashboards prontos
   (rua, pista/pull, aquecimento, diagnóstico), log .msl em segundo plano, tela sempre ligada.
2. **Alertas de segurança** — IAT alta, CLT alta, lambda pobre com carga (MAP > X), EGO saturado,
   duty de bico > 85%, overboost. Som/vibração/tela vermelha. (Ligado ao problema dos pistões.)
3. **Visualização do mapa** — ler páginas, mostrar VE/ignição/lambda como heatmap com cursor ao vivo
   (célula atual), curvas (WUE, MAT retard etc.), exportar .msq.
4. **Edição do mapa** — editar célula/região (+/− %, suavizar, interpolar), enviar para RAM,
   verificar CRC32 da página, botão Burn explícito; backup automático do .msq antes de cada escrita;
   bloquear edição acima de X km/h/rpm; histórico/undo de alterações.
5. **Análise** — sugestões de VE a partir dos logs (estilo VE Analyze), relatório de uma rodada
   (máx IAT, momentos pobres em carga etc.); opcional: enviar log para análise por IA.

## Segurança (requisito, não detalhe)
- Nunca escrever sem: backup local, verificação de signature, CRC pós-escrita, confirmação do usuário
- Burn separado do "enviar"; nada de escrita automática em loop
- Validar limites min/max do INI antes de enviar qualquer valor

## Decisões tomadas
- **Conexão: Bluetooth** — prioridade Bluetooth clássico SPP (HC-05/06, o mais comum em MS); BLE (HM-10 e similares)
  na sequência, porque muitos usuários terão módulos BLE; USB-OTG fica para depois (camada `transport` já abstrata).
- **Público: produto para outros** — implica:
  - **INI-driven de verdade**: nenhum offset hardcoded; ao conectar, o app lê a signature e pede/associa o INI correspondente.
    Usuário importa o INI (do projeto TunerStudio ou do pacote do firmware) — evita redistribuir INIs de terceiros sem licença.
  - Suporte progressivo: MS2/Extra (incl. variantes BR como MegaSpeed) → MS3 → MS1/Extra (protocolo legado sem envelope).
  - Dashboards configuráveis a partir de **templates** que mapeiam para canais padrão (rpm, map, afr/lambda, clt, mat, tps...)
    com fallback por nome do canal no INI.
  - Idiomas PT-BR e EN; aviso de responsabilidade (risco ao motor) no primeiro uso e antes de qualquer escrita.
  - Android 12+: permissões BLUETOOTH_CONNECT/SCAN; tela sempre ligada; serviço em primeiro plano para log.
  - Publicação na Play Store; modelo de negócio a definir (grátis com dash básico / pago com edição e análise?).
- **Edição de mapa: depois do dash e alertas** — fases 1–3 são somente leitura; escrita (fase 4) só após comunicação
  comprovada no Vectra e em pelo menos um outro firmware.

## Testes para um produto multi-ECU
- **Simulador de ECU dirigido por INI + .msq**: responde Q/S/r/w/b/k e envia canais reproduzindo um .msl.
  Permite CI sem carro e testar firmwares que não temos fisicamente.
- Coleção de INIs de exemplo já presente na máquina (`TunerStudioProjects`: MS1_BG_3.0, MS1_Extra_Example,
  MS2-Extra-Example, MS3-Example_Project, MSII_BG_2.905_Example, Vectra16vT) como suíte de teste do parser.

## Verificação (quando for implementar)
- Testes unitários: parser do INI contra `mainController.ini` real; decodificar `2026-10-15_12.09.45.msq` e comparar valores
  (ex.: reqFuel 7.8, veTable1 16x16) com o que o TunerStudio mostra
- Simulador replay de .msl antes de ir ao carro
- No carro: primeiro só leitura; comparar valores com o TunerStudio lado a lado; escrita só testada com motor desligado
