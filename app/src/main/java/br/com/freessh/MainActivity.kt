package br.com.freessh

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Properties

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { FreeSshApp(ProfileStore(this), HostKeyStore(this)) } } }
}

data class SshProfile(val name:String,val host:String,val port:Int=22,val user:String,val password:String="",val privateKey:String="",val passphrase:String="",val timeout:Int=15,val keepAlive:Int=30)
class ProfileStore(context:Context){private val prefs=context.getSharedPreferences("profiles",MODE_PRIVATE);fun load():List<SshProfile>=runCatching{val a=JSONArray(prefs.getString("items","[]"));(0 until a.length()).map{i->a.getJSONObject(i).let{o->SshProfile(o.getString("name"),o.getString("host"),o.optInt("port",22),o.getString("user"),o.optString("password"),o.optString("privateKey"),o.optString("passphrase"),o.optInt("timeout",15),o.optInt("keepAlive",30))}}}.getOrDefault(emptyList());fun save(items:List<SshProfile>){val a=JSONArray();items.forEach{p->a.put(JSONObject().apply{put("name",p.name);put("host",p.host);put("port",p.port);put("user",p.user);put("password",p.password);put("privateKey",p.privateKey);put("passphrase",p.passphrase);put("timeout",p.timeout);put("keepAlive",p.keepAlive)})};prefs.edit().putString("items",a.toString()).apply()}}
class HostKeyStore(context:Context){private val prefs=context.getSharedPreferences("trusted_host_keys",MODE_PRIVATE);fun get(host:String,port:Int)=prefs.getString("$host:$port",null);fun trust(host:String,port:Int,key:String)=prefs.edit().putString("$host:$port",key).apply()}

data class HostPrompt(val fingerprint:String,val key:String,val changed:Boolean)

@Composable fun FreeSshApp(store:ProfileStore,hostKeys:HostKeyStore){var profiles by remember{mutableStateOf(store.load())};var selected by remember{mutableStateOf<SshProfile?>(null)};if(selected!=null)TerminalScreen(selected!!,hostKeys){selected=null}else ConnectionScreen(profiles,{p->profiles=profiles+p;store.save(profiles)},{p->selected=p},{p->profiles=profiles-p;store.save(profiles)})}
@Composable fun ConnectionScreen(profiles:List<SshProfile>,onSave:(SshProfile)->Unit,onConnect:(SshProfile)->Unit,onDelete:(SshProfile)->Unit){var name by remember{mutableStateOf("")};var host by remember{mutableStateOf("")};var port by remember{mutableStateOf("22")};var user by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var key by remember{mutableStateOf("")};var passphrase by remember{mutableStateOf("")};var timeout by remember{mutableStateOf("15")};var keepAlive by remember{mutableStateOf("30")};fun profile()=SshProfile(name.ifBlank{"SSH $host"},host,port.toIntOrNull()?:22,user,password,key,passphrase,timeout.toIntOrNull()?:15,keepAlive.toIntOrNull()?:30);LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("FreeSSH",style=MaterialTheme.typography.headlineLarge);Text("Cliente SSH Android gratuito e sem anúncios")};item{OutlinedTextField(name,{name=it},label={Text("Nome do perfil")},modifier=Modifier.fillMaxWidth());OutlinedTextField(host,{host=it},label={Text("IP / hostname")},modifier=Modifier.fillMaxWidth());OutlinedTextField(port,{port=it},label={Text("Porta SSH")},modifier=Modifier.fillMaxWidth());OutlinedTextField(user,{user=it},label={Text("Usuário")},modifier=Modifier.fillMaxWidth());OutlinedTextField(password,{password=it},label={Text("Senha")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())};item{Text("Opções avançadas",style=MaterialTheme.typography.titleMedium);OutlinedTextField(key,{key=it},label={Text("Chave privada (texto, opcional)")},modifier=Modifier.fillMaxWidth());OutlinedTextField(passphrase,{passphrase=it},label={Text("Passphrase da chave")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(timeout,{timeout=it},label={Text("Timeout s")},modifier=Modifier.weight(1f));OutlinedTextField(keepAlive,{keepAlive=it},label={Text("Keepalive s")},modifier=Modifier.weight(1f))}};item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({if(host.isNotBlank()&&user.isNotBlank())onConnect(profile())}){Text("Conectar")};OutlinedButton({if(host.isNotBlank()&&user.isNotBlank())onSave(profile())}){Text("Salvar perfil")}};HorizontalDivider();Text("Perfis salvos",style=MaterialTheme.typography.titleLarge)};items(profiles){p->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Column(Modifier.weight(1f)){Text(p.name,style=MaterialTheme.typography.titleMedium);Text("${p.user}@${p.host}:${p.port}")};Button({onConnect(p)}){Text("Abrir")};IconButton({onDelete(p)}){Icon(Icons.Default.Delete,"Excluir")}}}}}}

@Composable fun TerminalScreen(p:SshProfile,hostKeys:HostKeyStore,onBack:()->Unit){
 var output by remember{mutableStateOf("Conectando a ${p.host}:${p.port}…\n")};var cmd by remember{mutableStateOf("")};var session by remember{mutableStateOf<Session?>(null)};var channel by remember{mutableStateOf<ChannelShell?>(null)};var stdin by remember{mutableStateOf<OutputStream?>(null)};var prompt by remember{mutableStateOf<HostPrompt?>(null)};var trustDecision by remember{mutableStateOf<Boolean?>(null)};val scroll=rememberScrollState()
 fun send(){val text=cmd;if(text.isBlank())return;cmd="";val writer=stdin;if(writer==null||channel?.isConnected!=true){output+="\n[terminal desconectado]\n";return};kotlin.concurrent.thread{runCatching{writer.write((text+"\n").toByteArray());writer.flush()}.onFailure{runOnUiThread{output+="\nErro ao enviar: ${it.message}\n"}}}}
 fun disconnect(){runCatching{channel?.disconnect()};runCatching{session?.disconnect()}}
 if(prompt!=null){AlertDialog(onDismissRequest={trustDecision=false},title={Text(if(prompt!!.changed)"ATENÇÃO: chave do servidor mudou" else "Confiar neste servidor?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("${p.host}:${p.port}");Text("Fingerprint:");Text(prompt!!.fingerprint,fontFamily=FontFamily.Monospace);if(prompt!!.changed)Text("A chave salva para este servidor é diferente. Isso pode indicar troca legítima do servidor ou ataque man-in-the-middle. Confirme somente se você reconhece a alteração.") else Text("Confirme a identidade do servidor antes de continuar. Esta chave será lembrada neste aparelho.")}},confirmButton={Button({trustDecision=true}){Text(if(prompt!!.changed)"Confiar na nova chave" else "Confiar")}},dismissButton={TextButton({trustDecision=false}){Text("Cancelar")}})}
 LaunchedEffect(p){withContext(Dispatchers.IO){runCatching{val j=JSch();if(p.privateKey.isNotBlank())j.addIdentity("profile",p.privateKey.toByteArray(),null,p.passphrase.ifBlank{null}?.toByteArray());val s=j.getSession(p.user,p.host,p.port);if(p.password.isNotBlank())s.setPassword(p.password);s.timeout=p.timeout*1000;s.serverAliveInterval=p.keepAlive*1000
   s.setConfig(Properties().apply{put("StrictHostKeyChecking","ask")});s.setUserInfo(object:UserInfo{override fun getPassword()=p.password;override fun promptYesNo(message:String):Boolean{val hk=s.hostKey;val current=hk?.key?:return false;val fp=hk.getFingerPrint(j);val saved=hostKeys.get(p.host,p.port);runOnUiThread{prompt=HostPrompt(fp,current,saved!=null&&saved!=current)};while(trustDecision==null)Thread.sleep(100);val ok=trustDecision==true;if(ok)hostKeys.trust(p.host,p.port,current);runOnUiThread{prompt=null};return ok};override fun getPassphrase()=p.passphrase;override fun promptPassphrase(message:String)=p.passphrase.isNotBlank();override fun promptPassword(message:String)=p.password.isNotBlank();override fun showMessage(message:String){runOnUiThread{output+="\n$message\n"}}})
   val saved=hostKeys.get(p.host,p.port);if(saved!=null){j.hostKeyRepository.add(HostKey(p.host,java.util.Base64.getDecoder().decode(saved)),null)}
   s.connect();session=s;val sh=s.openChannel("shell") as ChannelShell;sh.setPty(true);sh.setPtyType("xterm-256color",100,40,800,600);val input=sh.inputStream;stdin=sh.outputStream;sh.connect();channel=sh;runOnUiThread{output+="Conectado — terminal PTY ativo.\n"};val buf=ByteArray(4096);while(sh.isConnected){val n=input.read(buf);if(n<0)break;if(n>0){val text=String(buf,0,n);runOnUiThread{output+=text}}};runOnUiThread{output+="\n[Sessão encerrada]\n"}
  }.onFailure{runOnUiThread{output+="Erro: ${it.message}\n"}}}}
 DisposableEffect(Unit){onDispose{disconnect()}}
 LaunchedEffect(output){scroll.animateScrollTo(scroll.maxValue)}
 Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Row{Text("${p.user}@${p.host}",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton({disconnect();onBack()}){Text("Voltar")}};Surface(Modifier.weight(1f).fillMaxWidth(),tonalElevation=2.dp){Text(output,fontFamily=FontFamily.Monospace,modifier=Modifier.padding(10.dp).verticalScroll(scroll))};Row{OutlinedTextField(value=cmd,onValueChange={cmd=it},label={Text("Terminal")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()},onDone={send()}),modifier=Modifier.weight(1f));Button(onClick={send()}){Text("Enviar")}}}
}
