package com.fish.yz.service;

import com.fish.yz.Repo;
import com.fish.yz.ServerInfoHolder;
import com.fish.yz.protobuf.Protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.org.apache.regexp.internal.RE;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Created by xscn2426 on 2016/11/23.
 * game提供的服务
 */
public class GameManagerServiceHandler extends SimpleChannelInboundHandler<Protocol.Request> {

	@Override
	protected void channelRead0(ChannelHandlerContext channelHandlerContext, Protocol.Request request) throws Exception {
		System.out.println("received message");

		switch (request.getCmdId()) {
			case FunctionalMessage:
				functionalMessage(channelHandlerContext, request);
				break;
			case EntityMessage:
				entityMessage(channelHandlerContext, request);
				break;
		}
	}

	// forward 转发处理
	public void entityMessage(ChannelHandlerContext ctx, Protocol.Request request) throws InvalidProtocolBufferException {
		forwardEntityMessage(ctx, request);
	}

	public void functionalMessage(ChannelHandlerContext ctx, Protocol.Request request) throws InvalidProtocolBufferException {
        Protocol.FunctionalMessage fm = request.getExtension(Protocol.FunctionalMessage.request);
        switch (fm.getFunc()){
            case REG_GAME:
                regGameServer(ctx, request);
                break;
            case REG_GATE:
                regGateServer(ctx, request);
                break;
            case SEND_GAMEINFO:
                sendGameServerInfo(ctx, request);
                break;
	        case REG_DB:
		        regDbServer(ctx, request);
		        break;
	        case REG_ENTITY:
	        	regEntity(ctx, request);
	        	break;
	        case UNREG_ENTITY:
	        	unregEntity(ctx, request);
	        	break;
	        //case FORWARD_ENTITY_MESSAGE:
	        //	forwardEntityMessage(ctx, request);
	        //	break;
        }
	}

	public void regEntity(ChannelHandlerContext ctx, Protocol.Request request) throws InvalidProtocolBufferException {
		System.out.println("reg entity " + request);
		Protocol.FunctionalMessage fm = request.getExtension(Protocol.FunctionalMessage.request);
		Protocol.GlobalEntityRegMsg msg = Protocol.GlobalEntityRegMsg.parseFrom(fm.getParameters());
		boolean regOK = false;
		if (!Repo.instance().entities.containsKey(msg.getEntityUniqName().toStringUtf8())){
			Protocol.EntityMailbox mailbox = msg.getMailbox();
			Repo.instance().entities.put(msg.getEntityUniqName().toStringUtf8(), mailbox);
			for (Channel channel : Repo.instance().games.values()){
				channel.writeAndFlush(request);
			}
			regOK = true;
		}
		if (msg.hasCallbackId()){
			Protocol.GmReturnVal.Builder replyBuilder = Protocol.GmReturnVal.newBuilder();
			replyBuilder.setType(Protocol.GmReturnVal.CallbackType.REG_ENTITY_MAILBOX);
			replyBuilder.setCallbackId(msg.getCallbackId());
			replyBuilder.setReturnStatus(regOK);

			Protocol.FunctionalMessage.Builder fb = Protocol.FunctionalMessage.newBuilder();
			fb.setFunc(Protocol.FunctionalMessage.FuncType.GMRETURNVAL);
			fb.setParameters(replyBuilder.build().toByteString());
			Protocol.Request.Builder rb = Protocol.Request.newBuilder();

			rb.setCmdId(Protocol.Request.CmdIdType.FunctionalMessage);
			rb.setExtension(Protocol.FunctionalMessage.request, fb.build());
			ctx.channel().writeAndFlush(fb.build());
		}
	}

	public void unregEntity(ChannelHandlerContext ctx, Protocol.Request request) throws InvalidProtocolBufferException {
		System.out.println("unreg entity " + request);
		Protocol.FunctionalMessage fm = request.getExtension(Protocol.FunctionalMessage.request);
		Protocol.GlobalEntityRegMsg msg = Protocol.GlobalEntityRegMsg.parseFrom(fm.getParameters());
		if (Repo.instance().entities.containsKey(msg.getEntityUniqName().toStringUtf8())){
			Repo.instance().entities.remove(msg.getEntityUniqName().toStringUtf8());
			for (Channel channel : Repo.instance().games.values()){
				channel.writeAndFlush(request);
			}
		}
	}

	public void forwardEntityMessage(ChannelHandlerContext ctx, Protocol.Request request) throws InvalidProtocolBufferException {
		System.out.println("forward entity message " + request);
		Protocol.EntityMessage em = request.getExtension(Protocol.EntityMessage.request);
		Protocol.ForwardMessageHeader msg = Protocol.ForwardMessageHeader.parseFrom(em.getRoutes());
		Protocol.EntityMailbox src = msg.getSrcmb();
		Protocol.EntityMailbox dst = msg.getDstmb();
		boolean forwardOk = false;
		if (dst.hasServerinfo()){
			Protocol.ServerInfo serverInfo = dst.getServerinfo();
			ServerInfoHolder serverInfoHolder = new ServerInfoHolder(serverInfo);
			Channel channel = Repo.instance().games.get(serverInfoHolder);
			if (channel != null){
				channel.writeAndFlush(request);
				forwardOk = true;
			}
		}
		if (msg.hasCallbackId()){
			Protocol.GmReturnVal.Builder replyBuilder = Protocol.GmReturnVal.newBuilder();
			replyBuilder.setType(Protocol.GmReturnVal.CallbackType.FORWARD_ENTITY_MSG);
			replyBuilder.setCallbackId(msg.getCallbackId());
			replyBuilder.setReturnStatus(forwardOk);

			Protocol.FunctionalMessage.Builder fb = Protocol.FunctionalMessage.newBuilder();
			fb.setFunc(Protocol.FunctionalMessage.FuncType.GMRETURNVAL);
			fb.setParameters(replyBuilder.build().toByteString());
			Protocol.Request.Builder rb = Protocol.Request.newBuilder();

			rb.setCmdId(Protocol.Request.CmdIdType.FunctionalMessage);
			rb.setExtension(Protocol.FunctionalMessage.request, fb.build());
			ctx.channel().writeAndFlush(fb.build());
		}
	}

	public void regGameServer(ChannelHandlerContext ctx, Protocol.Request request){
        System.out.println("client reg game server \n" + request);
        ServerInfoHolder holder = getServerInfoHolder(ctx, request);
		if (Repo.instance().games.containsKey(holder))
			Repo.instance().games.get(holder).close();
		holder.type = ServerInfoHolder.ServerType.Game;
        Repo.instance().games.put(holder, ctx.channel());
		ctx.channel().attr(Repo.serverInfoKey).set(holder);

		updateGameServerInfo();
    }

    public void regGateServer(ChannelHandlerContext ctx, Protocol.Request request){
        System.out.println("client reg gate server \n" + request);
        ServerInfoHolder holder = getServerInfoHolder(ctx, request);
	    if (Repo.instance().gates.containsKey(holder))
		    Repo.instance().gates.get(holder).close();
	    holder.type = ServerInfoHolder.ServerType.Gate;
        Repo.instance().gates.put(holder, ctx.channel());
	    ctx.channel().attr(Repo.serverInfoKey).set(holder);
    }

	public void regDbServer(ChannelHandlerContext ctx, Protocol.Request request){
		System.out.println("client reg db server \n" + request);
		ServerInfoHolder holder = getServerInfoHolder(ctx, request);
		if (Repo.instance().dbs.containsKey(holder))
			Repo.instance().dbs.get(holder).close();
		holder.type = ServerInfoHolder.ServerType.Db;
		Repo.instance().dbs.put(holder, ctx.channel());
		ctx.channel().attr(Repo.serverInfoKey).set(holder);
	}

    private ServerInfoHolder getServerInfoHolder(ChannelHandlerContext ctx, Protocol.Request request){
        Protocol.FunctionalMessage fm = request.getExtension(Protocol.FunctionalMessage.request);
        String ip = ((InetSocketAddress)ctx.channel().remoteAddress()).getHostName();
        Protocol.ServerInfo.Builder siBuilder = Protocol.ServerInfo.newBuilder();
        siBuilder.setIp(ByteString.copyFromUtf8(ip));
        siBuilder.setPort(Integer.parseInt(fm.getParameters().toStringUtf8()));
        Protocol.ServerInfo si = siBuilder.build();
        return new ServerInfoHolder(si);
    }

	public void sendGameServerInfo(ChannelHandlerContext ctx, Protocol.Request request) {
		System.out.println("client want get game server infos \n" + request);
		realSendGameServerInfo(ctx.channel());
	}

	public void realSendGameServerInfo(Channel channel){
		Protocol.GameServerInfos.Builder gsi = Protocol.GameServerInfos.newBuilder();
		int i = 0;
		for (ServerInfoHolder sih : Repo.instance().games.keySet()) {
			gsi.addGameservers(i, sih.si);
			i++;
		}

		Protocol.Request.Builder requestBuilder = Protocol.Request.newBuilder();
		requestBuilder.setCmdId(Protocol.Request.CmdIdType.FunctionalMessage);
		Protocol.FunctionalMessage.Builder fmBuilder = Protocol.FunctionalMessage.newBuilder();
		fmBuilder.setFunc(Protocol.FunctionalMessage.FuncType.SEND_GAMEINFO);
		fmBuilder.setParameters(gsi.build().toByteString());
		requestBuilder.setExtension(Protocol.FunctionalMessage.request, fmBuilder.build());
		Protocol.Request reply = requestBuilder.build();

		channel.writeAndFlush(reply);
	}

	public void updateGameServerInfo(){
		// 暂时设定给game和gate都发送，看情况决定需要
		for (Map.Entry<ServerInfoHolder, Channel> entry : Repo.instance().games.entrySet()){
			realSendGameServerInfo(entry.getValue());
		}
		for (Map.Entry<ServerInfoHolder, Channel> entry : Repo.instance().gates.entrySet()){
			realSendGameServerInfo(entry.getValue());
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		System.out.println("exceptionCaught in gm " + cause.getMessage());
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		ServerInfoHolder holder = ctx.channel().attr(Repo.serverInfoKey).get();
		switch (holder.type){
			case Game:
				Repo.instance().games.remove(holder);
				updateGameServerInfo();
				break;
			case Db:
				Repo.instance().dbs.remove(holder);
				break;
			case Gate:
				Repo.instance().gates.remove(holder);
				break;
		}
		ctx.channel().close();
	}
}