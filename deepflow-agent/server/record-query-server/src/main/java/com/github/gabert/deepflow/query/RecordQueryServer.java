package com.github.gabert.deepflow.query;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetSocketAddress;

public class RecordQueryServer {
    private final QueryServerConfig config;
    private final ClickHouseClient ch;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public RecordQueryServer(QueryServerConfig config) {
        this.config = config;
        this.ch = new ClickHouseClient(config);
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel sc) {
                        sc.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(1 << 20),
                                new QueryHandler(ch, config.getCorsOrigin()));
                    }
                });

        serverChannel = bootstrap.bind(config.getServerPort()).sync().channel();
        System.out.println("RecordQueryServer started on port " + getPort()
                + " (CORS origin: " + config.getCorsOrigin() + ")");
    }

    public int getPort() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    public static void main(String[] args) throws Exception {
        QueryServerConfig config = QueryServerConfig.load(args);
        RecordQueryServer server = new RecordQueryServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        server.serverChannel.closeFuture().sync();
    }
}
