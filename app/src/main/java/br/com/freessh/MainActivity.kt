package br.com.freessh

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardActions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { FreeSshApp(ProfileStore(this)) } } }
}

data class SshProfile(val name:String,val host:String,val port:Int=22,val user:String,val password:String="",val privateKey:String="",val passphrase:String="",val timeout:Int=15,val keepAlive:Int=30)
class ProfileStore(context:android.content.Context){private val prefs=context.getSharedPreferences("profiles",MODE_PRIVATE);fun load():List<SshProfile>=runCatching{val a=JSONArray(prefs.getString("items","[]"));(0 until a.length()).map{i->a.getJSONObject(i).let{o->SshProfile(o.getString("name"),o.getString("host"),o.optInt("port",22),o.getString("user"),o.optString("password"),o.optString("privateKey"),o.optString("passphrase"),o.optInt("timeout",15),o.optInt("keepAlive",30))}}}.getOrDefault(emptyList());fun save(items:List<SshProfile>){val a=JSONArray();items.forEach{p->a.put(JSONObject().apply{put("name",p.name);put("host",p.host);put("port",p.port);put("user",p.user);put("password",p.password);put("privateKey",p.privateKey);put("passphrase",p.passphrase);put("timeout",p.timeout);put("keepAlive",p.keepAlive)})};prefs.edit().putString("items",a.toString()).apply()}}

@Composable fun FreeSshApp(store:ProfileStore){var profiles by remember{mutableStateOf(store.load())};var selected by remember{mutableStateOf<SshProfile?>(null)};if(selected!=null)TerminalScreen(selected!!){selected=null}else ConnectionScreen(profiles,{p->profiles=profiles+p;store.save(profiles)},{p->selected=p},{p->profiles=profiles-p;store.save(profiles)})}
@Composable fun ConnectionScreen(profiles:List<SshProfile>,onSave:(SshProfile)->Unit,onConnect:(SshProfile)->Unit,onDelete:(SshProfile)->Unit){var name by remember{mutableStateOf("")};var host by remember{mutableStateOf("")};var port by remember{mutableStateOf("22")};var user by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var key by remember{mutableStateOf("")};var passphrase by remember{mutableStateOf("")};var timeout by remember{mutableStateOf("15")};var keepAlive by remember{mutableStateOf("30")};fun profile()=SshProfile(name.ifBlank{"SSH $host"},host,port.toIntOrNull()?:22,user,password,key,passphrase,timeout.toIntOrNull()?:15,keepAlive.toIntOrNull()?:30);LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("FreeSSH",style=MaterialTheme.typography.headlineLarge);Text("Cliente SSH Android gratuito e sem anúncios")};item{OutlinedTextField(name,{name=it},label={Text("Nome do perfil")},modifier=Modifier.fillMaxWidth());OutlinedTextField(host,{host=it},label={Text("IP / hostname")},modifier=Modifier.fillMaxWidth());OutlinedTextField(port,{port=it},label={Text("Porta SSH")},modifier=Modifier.fillMaxWidth());OutlinedTextField(user,{user=it},label={Text("Usuário")},modifier=Modifier.fillMaxWidth());OutlinedTextField(password,{password=it},label={Text("Senha")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())};item{Text("Opções avançadas",style=MaterialTheme.typography.titleMedium);OutlinedTextField(key,{key=it},label={Text("Chave privada (texto, opcional)")},modifier=Modifier.fillMaxWidth());OutlinedTextField(passphrase,{passphrase=it},label={Text("Passphrase da chave")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(timeout,{timeout=it},label={Text("Timeout s")},modifier=Modifier.weight(1f));OutlinedTextField(keepAlive,{keepAlive=it},label={Text("Keepalive s")},modifier=Modifier.weight(1f))}};item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({if(host.isNotBlank()&&user.isNotBlank())onConnect(profile())}){Text("Conectar")};OutlinedButton({if(host.isNotBlank()&&user.isNotBlank())onSave(profile())}){Text("Salvar perfil")}};HorizontalDivider();Text("Perfis salvos",style=MaterialTheme.typography.titleLarge)};items(profiles){p->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Column(Modifier.weight(1f)){Text(p.name,style=MaterialTheme.typography.titleMedium);Text("${p.user}@${p.host}:${p.port}")};Button({onConnect(p)}){Text("Abrir")};IconButton({onDelete(p)}){Icon(Icons.Default.Delete,"Excluir")}}}}}}

@Composable fun TerminalScreen(p:SshProfile,onBack:()->Unit){
 var output by remember{mutableStateOf("Conectando a ${p.host}:${p.port}…\n")};var cmd by remember{mutableStateOf("")};var session by remember{mutableStateOf<com.jcraft.jsch.Session?>(null)};val scroll=rememberScrollState()
 fun send(){val c=cmd.trim();if(c.isBlank())return;cmd="";val s=session;if(s==null||!s.isConnected){output+="Não conectado.\n";return};kotlin.concurrent.thread{runCatching{val ch=s.openChannel("exec") as com.jcraft.jsch.ChannelExec;ch.setCommand(c);ch.setInputStream(null);val stdout=ch.inputStream;val stderr=ch.errStream;ch.connect();val out=stdout.bufferedReader().readText();val err=stderr?.bufferedReader()?.readText().orEmpty();ch.disconnect();runOnUiThread{output+="$ $c\n$out${if(err.isNotBlank())"\n$err" else ""}\n"}}.onFailure{runOnUiThread{output+="Erro: ${it.message}\n"}}}}
 LaunchedEffect(p){withContext(Dispatchers.IO){runCatching{val j=JSch();if(p.privateKey.isNotBlank())j.addIdentity("profile",p.privateKey.toByteArray(),null,p.passphrase.ifBlank{null}?.toByteArray());val s=j.getSession(p.user,p.host,p.port);if(p.password.isNotBlank())s.setPassword(p.password);s.setConfig("StrictHostKeyChecking","no");s.timeout=p.timeout*1000;s.serverAliveInterval=p.keepAlive*1000;s.connect();session=s;runOnUiThread{output+="Conectado. Digite um comando e pressione Enter.\n"}}.onFailure{runOnUiThread{output+="Erro: ${it.message}\n"}}}}
 LaunchedEffect(output){scroll.animateScrollTo(scroll.maxValue)}
 Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row{Text("${p.user}@${p.host}",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton({session?.disconnect();onBack()}){Text("Voltar")}};Surface(Modifier.weight(1f).fillMaxWidth(),tonalElevation=2.dp){Text(output,Modifier.padding(12.dp).verticalScroll(scroll))};Row{OutlinedTextField(value=cmd,onValueChange={cmd=it},label={Text("Comando")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()},onDone={send()}),modifier=Modifier.weight(1f));Button(onClick={send()}){Text("Enviar")}}}
}
