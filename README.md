# FreeSSH

Cliente SSH Android gratuito, sem anúncios e de código aberto.

## Recursos v1
- Conexão por IP ou hostname e porta (padrão 22)
- Usuário + senha
- Autenticação opcional por chave privada + passphrase
- Timeout e SSH keepalive configuráveis
- Perfis salvos para conexão rápida
- Execução de comandos e visualização da saída
- Android 8.0+ (API 26)

## Parâmetros SSH importantes
Os parâmetros essenciais são host/IP, porta, usuário e um método de autenticação (senha ou chave privada). A tela inicial também expõe nome do perfil, passphrase, timeout e keepalive. Evoluções previstas: terminal PTY realmente interativo, importação segura de chave via Storage Access Framework, known_hosts/fingerprint, túnel/port forwarding, proxy/jump host, SFTP e criptografia dos segredos via Android Keystore.

## Compilar
Abra no Android Studio com JDK 17, sincronize o Gradle e execute o módulo `app`.

> Segurança: esta primeira versão funcional salva os perfis localmente. Antes de distribuir publicamente, migre senha/passphrase para Android Keystore/EncryptedSharedPreferences e implemente verificação persistente de host keys.
