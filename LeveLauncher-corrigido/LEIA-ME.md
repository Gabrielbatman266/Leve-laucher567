# Leve Launcher

Tela inicial só com texto, sem ícones, widgets nem animações, para gastar o mínimo de RAM.

- **Toque** num app: abre.
- **Toque longo** num app: tela de informações (desinstalar / forçar parada).
- **Busca**: digite e aperte Enter para abrir o primeiro resultado.
- **Toque no relógio**: alarmes. **Toque longo no relógio**: Configurações.

## Como gerar o APK pelo GitHub

1. Crie uma conta em https://github.com e clique em **New repository**. Dê um nome (ex.: `LeveLauncher`), marque **Public** e crie.
2. Na página do repositório, clique em **uploading an existing file**.
3. Extraia o ZIP no PC e **arraste o conteúdo da pasta LeveLauncher** (as pastas `app`, `.github` e os arquivos) para a página. Clique em **Commit changes**.
4. Abra a aba **Actions** e espere o "Gerar APK" ficar verde (2 a 5 minutos).
5. Na página inicial do repositório, clique em **Releases** e baixe o `LeveLauncher.apk` no celular.

Se a pasta `.github` não subir (às vezes o navegador esconde pastas com ponto):
**Add file → Create new file**, nome `.github/workflows/build.yml`, cole o conteúdo desse arquivo e salve.

## Como instalar no Xiaomi

1. Abra o APK baixado e permita "instalar apps desconhecidos" para o navegador/arquivos.
2. Vá em **Configurações → Apps → Gerenciar apps → Apps padrão → Tela inicial** e escolha **Leve Launcher**.
3. Para voltar ao launcher da Xiaomi, repita o passo 2 e escolha o original.
