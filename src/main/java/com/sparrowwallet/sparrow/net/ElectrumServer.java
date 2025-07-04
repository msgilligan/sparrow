package com.sparrowwallet.sparrow.net;

import com.google.common.eventbus.Subscribe;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.Version;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.bip47.InvalidPaymentCodeException;
import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.BlockSummary;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.net.cormorant.Cormorant;
import com.sparrowwallet.sparrow.net.cormorant.bitcoind.CormorantBitcoindException;
import com.sparrowwallet.sparrow.paynym.PayNym;
import com.sparrowwallet.sparrow.paynym.PayNymService;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ElectrumServer {
    private static final Logger log = LoggerFactory.getLogger(ElectrumServer.class);

    private static final String[] SUPPORTED_VERSIONS = new String[]{"1.3", "1.4.2"};

    private static final Version ELECTRS_MIN_BATCHING_VERSION = new Version("0.9.0");

    private static final Version FULCRUM_MIN_BATCHING_VERSION = new Version("1.6.0");

    private static final Version MEMPOOL_ELECTRS_MIN_BATCHING_VERSION = new Version("3.1.0");

    public static final String CORE_ELECTRUM_HOST = "127.0.0.1";

    private static final int MINIMUM_BROADCASTS = 2;

    public static final BlockTransaction UNFETCHABLE_BLOCK_TRANSACTION = new BlockTransaction(Sha256Hash.ZERO_HASH, 0, null, null, null);

    private static CloseableTransport transport;

    private static final Map<String, List<String>> subscribedScriptHashes = new ConcurrentHashMap<>();

    private static Server previousServer;

    private static final Map<String, String> retrievedScriptHashes = Collections.synchronizedMap(new HashMap<>());

    private static final Map<Sha256Hash, BlockTransaction> retrievedTransactions = new ConcurrentHashMap<>();

    private static final Map<Integer, BlockHeader> retrievedBlockHeaders = new ConcurrentHashMap<>();

    private static final Map<Sha256Hash, BlockTransaction> broadcastedTransactions = new ConcurrentHashMap<>();

    private static final Set<String> sameHeightTxioScriptHashes = ConcurrentHashMap.newKeySet();

    private final static Map<String, Integer> subscribedRecent = new ConcurrentHashMap<>();

    private final static Map<String, String> broadcastRecent = new ConcurrentHashMap<>();

    private static ElectrumServerRpc electrumServerRpc = new SimpleElectrumServerRpc();

    private static Cormorant cormorant;

    private static Server coreElectrumServer;

    private static ServerCapability serverCapability;

    private static final Pattern RPC_WALLET_LOADING_PATTERN = Pattern.compile(".*\"(Wallet loading failed[:.][^\"]*)\".*");

    private static synchronized CloseableTransport getTransport() throws ServerException {
        if(transport == null) {
            try {
                Server electrumServer = null;
                File electrumServerCert = null;
                String proxyServer = null;

                if(Config.get().getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER) {
                    electrumServer = Config.get().getPublicElectrumServer();
                    proxyServer = Config.get().getProxyServer();
                } else if(Config.get().getServerType() == ServerType.BITCOIN_CORE) {
                    if(coreElectrumServer == null) {
                        throw new ServerConfigException("Could not connect to Bitcoin Core RPC");
                    }
                    electrumServer = coreElectrumServer;
                    if(previousServer != null && previousServer.getUrl().contains(CORE_ELECTRUM_HOST)) {
                        previousServer = coreElectrumServer;
                    }
                } else if(Config.get().getServerType() == ServerType.ELECTRUM_SERVER) {
                    electrumServer = Config.get().getElectrumServer();
                    electrumServerCert = Config.get().getElectrumServerCert();
                    proxyServer = Config.get().getProxyServer();
                }

                if(electrumServer == null) {
                    throw new ServerConfigException("Electrum server URL not specified");
                }

                if(electrumServerCert != null && !electrumServerCert.exists()) {
                    throw new ServerConfigException("Electrum server certificate file not found");
                }

                Protocol protocol = electrumServer.getProtocol();

                //If changing server, don't rely on previous transaction history
                if(previousServer != null && !electrumServer.equals(previousServer)) {
                    retrievedScriptHashes.clear();
                    retrievedTransactions.clear();
                    retrievedBlockHeaders.clear();
                    TransactionHistoryService.walletLocks.values().forEach(walletLock -> walletLock.initialized = false);
                }
                previousServer = electrumServer;

                HostAndPort hostAndPort = electrumServer.getHostAndPort();
                boolean localNetworkAddress = !Protocol.isOnionAddress(hostAndPort) && !PublicElectrumServer.isPublicServer(hostAndPort)
                        && IpAddressMatcher.isLocalNetworkAddress(hostAndPort.getHost());

                if(!localNetworkAddress && Config.get().isUseProxy() && proxyServer != null && !proxyServer.isBlank()) {
                    HostAndPort proxy = HostAndPort.fromString(proxyServer);
                    if(electrumServerCert != null) {
                        transport = protocol.getTransport(hostAndPort, electrumServerCert, proxy);
                    } else {
                        transport = protocol.getTransport(hostAndPort, proxy);
                    }
                } else {
                    if(electrumServerCert != null) {
                        transport = protocol.getTransport(hostAndPort, electrumServerCert);
                    } else {
                        transport = protocol.getTransport(hostAndPort);
                    }
                }
            } catch (Exception e) {
                throw new ServerConfigException(e);
            }
        }

        return transport;
    }

    public void connect() throws ServerException {
        CloseableTransport closeableTransport = getTransport();
        closeableTransport.connect();
    }

    public void ping() throws ServerException {
        electrumServerRpc.ping(getTransport());
    }

    public List<String> getServerVersion() throws ServerException {
        return electrumServerRpc.getServerVersion(getTransport(), "Sparrow", SUPPORTED_VERSIONS);
    }

    public String getServerBanner() throws ServerException {
        return electrumServerRpc.getServerBanner(getTransport());
    }

    public BlockHeaderTip subscribeBlockHeaders() throws ServerException {
        return electrumServerRpc.subscribeBlockHeaders(getTransport());
    }

    public static synchronized boolean isConnected() {
        if(transport != null) {
            TcpTransport tcpTransport = (TcpTransport)transport;
            return tcpTransport.isConnected();
        }

        return false;
    }

    public static synchronized void closeActiveConnection() throws ServerException {
        if(transport != null) {
            closeConnection(transport);
            transport = null;
        }
    }

    private static void closeConnection(Closeable closeableTransport) throws ServerException {
        try {
            closeableTransport.close();
        } catch (IOException e) {
            throw new ServerException(e);
        }
    }

    private static void addCalculatedScriptHashes(Wallet wallet) {
        getCalculatedScriptHashes(wallet).forEach(retrievedScriptHashes::putIfAbsent);
    }

    private static void addCalculatedScriptHashes(WalletNode walletNode) {
        Map<String, String> calculatedScriptHashStatuses = new HashMap<>();
        addScriptHashStatus(calculatedScriptHashStatuses, walletNode);
        calculatedScriptHashStatuses.forEach(retrievedScriptHashes::putIfAbsent);
    }

    private static Map<String, String> getCalculatedScriptHashes(Wallet wallet) {
        Map<String, String> storedScriptHashStatuses = new HashMap<>();
        storedScriptHashStatuses.putAll(calculateScriptHashes(wallet, KeyPurpose.RECEIVE));
        storedScriptHashStatuses.putAll(calculateScriptHashes(wallet, KeyPurpose.CHANGE));
        return storedScriptHashStatuses;
    }

    private static Map<String, String> calculateScriptHashes(Wallet wallet, KeyPurpose keyPurpose) {
        Map<String, String> calculatedScriptHashes = new LinkedHashMap<>();
        for(WalletNode walletNode : wallet.getNode(keyPurpose).getChildren()) {
            addScriptHashStatus(calculatedScriptHashes, walletNode);
        }

        return calculatedScriptHashes;
    }

    private static void addScriptHashStatus(Map<String, String> calculatedScriptHashes, WalletNode walletNode) {
        String scriptHash = getScriptHash(walletNode);
        String scriptHashStatus = getScriptHashStatus(scriptHash, walletNode);
        calculatedScriptHashes.put(scriptHash, scriptHashStatus);
    }

    private static String getScriptHashStatus(String scriptHash, WalletNode walletNode) {
        List<ScriptHashTx> scriptHashTxes = getScriptHashes(scriptHash, walletNode);
        return getScriptHashStatus(scriptHashTxes);
    }

    private static List<ScriptHashTx> getScriptHashes(String scriptHash, WalletNode walletNode) {
        List<BlockTransactionHashIndex> txos  = new ArrayList<>(walletNode.getTransactionOutputs());
        txos.addAll(walletNode.getTransactionOutputs().stream().filter(BlockTransactionHashIndex::isSpent).map(BlockTransactionHashIndex::getSpentBy).collect(Collectors.toList()));
        Set<Sha256Hash> unique = new HashSet<>(txos.size());
        txos.removeIf(ref -> !unique.add(ref.getHash()));
        txos.sort((txo1, txo2) -> {
            if(txo1.getHeight() != txo2.getHeight()) {
                return txo1.getComparisonHeight() - txo2.getComparisonHeight();
            }

            if(txo1.isSpent() && txo1.getSpentBy().equals(txo2)) {
                return -1;
            }

            if(txo2.isSpent() && txo2.getSpentBy().equals(txo1)) {
                return 1;
            }

            //We cannot further sort by order within a block, so sometimes multiple txos to an address will mean an incorrect status
            //Save a record of these to avoid triggering an AllHistoryChangedEvent based on potentially incorrect calculated statuses
            sameHeightTxioScriptHashes.add(scriptHash);
            return 0;
        });

        return txos.stream().map(txo -> new ScriptHashTx(txo.getHeight(), txo.getHashAsString(), txo.getFee() == null ? 0 : txo.getFee())).toList();
    }

    private static String getScriptHashStatus(List<ScriptHashTx> scriptHashTxes) {
        if(!scriptHashTxes.isEmpty()) {
            StringBuilder scriptHashStatus = new StringBuilder();
            for(ScriptHashTx scriptHashTx : scriptHashTxes) {
                scriptHashStatus.append(scriptHashTx.tx_hash).append(":").append(scriptHashTx.height).append(":");
            }

            return Utils.bytesToHex(Sha256Hash.hash(scriptHashStatus.toString().getBytes(StandardCharsets.UTF_8)));
        } else {
            return null;
        }
    }

    public static void clearRetrievedScriptHashes(Wallet wallet) {
        wallet.getNode(KeyPurpose.RECEIVE).getChildren().stream().map(ElectrumServer::getScriptHash).forEach(ElectrumServer::clearRetrievedScriptHash);
        wallet.getNode(KeyPurpose.CHANGE).getChildren().stream().map(ElectrumServer::getScriptHash).forEach(ElectrumServer::clearRetrievedScriptHash);
        TransactionHistoryService.walletLocks.computeIfAbsent(wallet.hashCode(), w -> new WalletLock()).initialized = false;
    }

    private static void clearRetrievedScriptHash(String scriptHash) {
        retrievedScriptHashes.remove(scriptHash);
        sameHeightTxioScriptHashes.remove(scriptHash);
    }

    public Map<WalletNode, Set<BlockTransactionHash>> getHistory(Wallet wallet) throws ServerException {
        Map<WalletNode, Set<BlockTransactionHash>> receiveTransactionMap = new TreeMap<>();
        getHistory(wallet, KeyPurpose.RECEIVE, receiveTransactionMap);

        Map<WalletNode, Set<BlockTransactionHash>> changeTransactionMap = new TreeMap<>();
        getHistory(wallet, KeyPurpose.CHANGE, changeTransactionMap);

        receiveTransactionMap.putAll(changeTransactionMap);
        return receiveTransactionMap;
    }

    public Map<WalletNode, Set<BlockTransactionHash>> getHistory(Wallet wallet, Collection<WalletNode> nodes) throws ServerException {
        Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap = new TreeMap<>();

        Set<WalletNode> historyNodes = new HashSet<>(nodes);
        //Add any nodes with mempool transactions in case these have been replaced
        Set<WalletNode> mempoolNodes = wallet.getWalletTxos().entrySet().stream()
                .filter(entry -> entry.getKey().getHeight() <= 0 || (entry.getKey().getSpentBy() != null && entry.getKey().getSpentBy().getHeight() <= 0))
                .map(Map.Entry::getValue)
                .collect(Collectors.toSet());
        historyNodes.addAll(mempoolNodes);

        subscribeWalletNodes(wallet, historyNodes, nodeTransactionMap, 0);
        getReferences(wallet, nodeTransactionMap.keySet(), nodeTransactionMap, 0);
        Set<BlockTransactionHash> newReferences = nodeTransactionMap.values().stream().flatMap(Collection::stream).filter(ref -> !wallet.getTransactions().containsKey(ref.getHash())).collect(Collectors.toSet());
        getReferencedTransactions(wallet, nodeTransactionMap);

        //Subscribe and retrieve transaction history from child nodes if necessary to maintain gap limit
        Set<KeyPurpose> keyPurposes = nodes.stream().map(WalletNode::getKeyPurpose).collect(Collectors.toUnmodifiableSet());
        for(KeyPurpose keyPurpose : keyPurposes) {
            WalletNode purposeNode = wallet.getNode(keyPurpose);
            getHistoryToGapLimit(wallet, nodeTransactionMap, purposeNode);
        }

        log.debug("Fetched nodes history for: " + nodeTransactionMap.keySet());

        if(!newReferences.isEmpty()) {
            //Look for additional nodes to fetch history for by considering the inputs and outputs of new transactions found
            log.debug(wallet.getFullName() + " found new transactions: " + newReferences);
            Set<WalletNode> additionalNodes = new HashSet<>();
            Map<String, WalletNode> walletScriptHashes = getAllScriptHashes(wallet);
            for(BlockTransactionHash reference : newReferences) {
                BlockTransaction blockTransaction = wallet.getTransactions().get(reference.getHash());
                for(TransactionOutput txOutput : blockTransaction.getTransaction().getOutputs()) {
                    WalletNode node = walletScriptHashes.get(getScriptHash(txOutput));
                    if(node != null && !historyNodes.contains(node)) {
                        additionalNodes.add(node);
                    }
                }

                for(TransactionInput txInput : blockTransaction.getTransaction().getInputs()) {
                    BlockTransaction inputBlockTransaction = wallet.getTransactions().get(txInput.getOutpoint().getHash());
                    if(inputBlockTransaction != null) {
                        TransactionOutput txOutput = inputBlockTransaction.getTransaction().getOutputs().get((int)txInput.getOutpoint().getIndex());
                        WalletNode node = walletScriptHashes.get(getScriptHash(txOutput));
                        if(node != null && !historyNodes.contains(node)) {
                            additionalNodes.add(node);
                        }
                    }
                }
            }

            if(!additionalNodes.isEmpty()) {
                log.debug("Found additional nodes: " + additionalNodes);
                subscribeWalletNodes(wallet, additionalNodes, nodeTransactionMap, 0);
                getReferences(wallet, additionalNodes, nodeTransactionMap, 0);
                getReferencedTransactions(wallet, nodeTransactionMap);
            }
        }

        return nodeTransactionMap;
    }

    public void getHistory(Wallet wallet, KeyPurpose keyPurpose, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap) throws ServerException {
        WalletNode purposeNode = wallet.getNode(keyPurpose);
        //Subscribe to all existing address WalletNodes and add them to nodeTransactionMap as keys to empty sets if they have history that needs to be fetched
        subscribeWalletNodes(wallet, getAddressNodes(wallet, purposeNode), nodeTransactionMap, 0);
        //All WalletNode keys in nodeTransactionMap need to have their history fetched (nodes without history will not be keys in the map yet)
        getReferences(wallet, nodeTransactionMap.keySet(), nodeTransactionMap, 0);
        //Fetch all referenced transaction to wallet transactions map. We do this now even though it is done again later to get it done before too many script hashes are subscribed
        getReferencedTransactions(wallet, nodeTransactionMap);
        //Increase child nodes if necessary to maintain gap limit, and ensure they are subscribed and history is fetched
        getHistoryToGapLimit(wallet, nodeTransactionMap, purposeNode);

        log.debug("Fetched history for: " + nodeTransactionMap.keySet());

        //Set the remaining WalletNode keys in nodeTransactionMap to empty sets to indicate no history (if no script hash history has already been retrieved in a previous call)
        getAddressNodes(wallet, purposeNode).stream().filter(node -> !nodeTransactionMap.containsKey(node) && retrievedScriptHashes.get(getScriptHash(node)) == null).forEach(node -> nodeTransactionMap.put(node, Collections.emptySet()));
    }

    private void getHistoryToGapLimit(Wallet wallet, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap, WalletNode purposeNode) throws ServerException {
        //Because node children are added sequentially in WalletNode.fillToIndex, we can simply look at the number of children to determine the highest filled index
        int historySize = purposeNode.getChildren().size();
        //The gap limit size takes the highest used index in the retrieved history and adds the gap limit (plus one to be comparable to the number of children since index is zero based)
        int gapLimitSize = getGapLimitSize(wallet, nodeTransactionMap, purposeNode);
        while(historySize < gapLimitSize) {
            purposeNode.fillToIndex(wallet, gapLimitSize - 1);
            subscribeWalletNodes(wallet, getAddressNodes(wallet, purposeNode), nodeTransactionMap, historySize);
            getReferences(wallet, nodeTransactionMap.keySet(), nodeTransactionMap, historySize);
            getReferencedTransactions(wallet, nodeTransactionMap);
            historySize = purposeNode.getChildren().size();
            gapLimitSize = getGapLimitSize(wallet, nodeTransactionMap, purposeNode);
        }
    }

    private Set<WalletNode> getAddressNodes(Wallet wallet, WalletNode purposeNode) {
        Integer watchLast = wallet.getWatchLast();
        if(watchLast == null || watchLast < wallet.getGapLimit() || wallet.getStoredBlockHeight() == null || wallet.getStoredBlockHeight() == 0 || wallet.getTransactions().isEmpty()) {
            return purposeNode.getChildren();
        }

        int highestUsedIndex = purposeNode.getChildren().stream().filter(WalletNode::isUsed).mapToInt(WalletNode::getIndex).max().orElse(0);
        int startFromIndex = highestUsedIndex - watchLast;
        return purposeNode.getChildren().stream().filter(walletNode -> walletNode.getIndex() >= startFromIndex).collect(Collectors.toCollection(TreeSet::new));
    }

    private int getGapLimitSize(Wallet wallet, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap, WalletNode purposeNode) {
        int highestIndex = nodeTransactionMap.keySet().stream().filter(node -> node.getDerivation().size() > 1 && purposeNode.getKeyPurpose() == node.getKeyPurpose())
                .map(WalletNode::getIndex).max(Comparator.comparing(Integer::valueOf)).orElse(-1);
        return highestIndex + wallet.getGapLimit() + 1;
    }

    public void getReferences(Wallet wallet, Collection<WalletNode> nodes, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap, int startIndex) throws ServerException {
        try {
            Map<WalletNode, ScriptHashTx[]> nodeHashHistory = new LinkedHashMap<>(nodes.size());
            Map<String, String> pathScriptHashes = new LinkedHashMap<>(nodes.size());
            for(WalletNode node : nodes) {
                if(node.getIndex() >= startIndex) {
                    pathScriptHashes.put(node.getDerivationPath(), getScriptHash(node));
                    nodeHashHistory.put(node, null);
                }
            }

            if(pathScriptHashes.isEmpty()) {
                return;
            }

            //Optimistic optimizations from guessing the script hash status based on known information
            for(Map.Entry<WalletNode, ScriptHashTx[]> entry : nodeHashHistory.entrySet()) {
                WalletNode node = entry.getKey();
                String scriptHash = pathScriptHashes.get(node.getDerivationPath());
                List<String> statuses = subscribedScriptHashes.get(scriptHash);

                if(statuses != null && !statuses.isEmpty()) {
                    //Optimize for new transactions that have been recently broadcasted
                    for(Sha256Hash txid : broadcastedTransactions.keySet()) {
                        BlockTransaction blkTx = broadcastedTransactions.get(txid);
                        if(blkTx.getTransaction().getOutputs().stream().map(ElectrumServer::getScriptHash).anyMatch(scriptHash::equals) ||
                            blkTx.getTransaction().getInputs().stream().map(txInput -> getPrevOutput(wallet, txInput))
                                    .filter(Objects::nonNull).map(ElectrumServer::getScriptHash).anyMatch(scriptHash::equals)) {
                            List<ScriptHashTx> scriptHashTxes = new ArrayList<>(getScriptHashes(scriptHash, node));
                            scriptHashTxes.add(new ScriptHashTx(0, txid.toString(), blkTx.getFee() == null ? 0 : blkTx.getFee()));

                            String status = getScriptHashStatus(scriptHashTxes);
                            if(Objects.equals(status, statuses.getLast())) {
                                entry.setValue(scriptHashTxes.toArray(new ScriptHashTx[0]));
                                pathScriptHashes.remove(node.getDerivationPath());
                            }
                        }
                    }

                    //Optimize for new confirmations should all pending transactions confirm at the current block height
                    if(entry.getValue() == null && AppServices.getCurrentBlockHeight() != null &&
                            node.getTransactionOutputs().stream().flatMap(txo -> txo.isSpent() ? Stream.of(txo, txo.getSpentBy()) : Stream.of(txo))
                                    .anyMatch(txo -> txo.getHeight() <= 0)) {
                        List<ScriptHashTx> scriptHashTxes = getScriptHashes(scriptHash, node);
                        for(ScriptHashTx scriptHashTx : scriptHashTxes) {
                            if(scriptHashTx.height <= 0) {
                                scriptHashTx.height = AppServices.getCurrentBlockHeight();
                                scriptHashTx.fee = 0;
                            }
                        }

                        String status = getScriptHashStatus(scriptHashTxes);
                        if(Objects.equals(status, statuses.getLast())) {
                            entry.setValue(scriptHashTxes.toArray(new ScriptHashTx[0]));
                            pathScriptHashes.remove(node.getDerivationPath());
                        }
                    }
                }
            }

            if(!pathScriptHashes.isEmpty()) {
                //Even if we have some successes, failure to retrieve all references will result in an incomplete wallet history. Don't proceed if that's the case.
                Map<String, ScriptHashTx[]> result = electrumServerRpc.getScriptHashHistory(getTransport(), wallet, pathScriptHashes, true);

                for(String path : result.keySet()) {
                    ScriptHashTx[] txes = result.get(path);

                    Optional<WalletNode> optionalNode = nodes.stream().filter(n -> n.getDerivationPath().equals(path)).findFirst();
                    if(optionalNode.isPresent()) {
                        WalletNode node = optionalNode.get();
                        nodeHashHistory.put(node, txes);
                    }
                }
            }

            for(WalletNode node : nodeHashHistory.keySet()) {
                ScriptHashTx[] txes = nodeHashHistory.get(node);

                //Some servers can return the same tx as multiple ScriptHashTx entries with different heights. Take the highest height only
                Set<BlockTransactionHash> references = Arrays.stream(txes).map(ScriptHashTx::getBlockchainTransactionHash)
                        .collect(TreeSet::new, (set, ref) -> {
                            Optional<BlockTransactionHash> optExisting = set.stream().filter(prev -> prev.getHash().equals(ref.getHash())).findFirst();
                            if(optExisting.isPresent()) {
                                if(optExisting.get().getHeight() < ref.getHeight()) {
                                    set.remove(optExisting.get());
                                    set.add(ref);
                                }
                            } else {
                                set.add(ref);
                            }
                        }, TreeSet::addAll);
                Set<BlockTransactionHash> existingReferences = nodeTransactionMap.get(node);

                if(existingReferences == null) {
                    nodeTransactionMap.put(node, references);
                } else {
                    for(BlockTransactionHash reference : references) {
                        if(!existingReferences.add(reference)) {
                            Optional<BlockTransactionHash> optionalReference = existingReferences.stream().filter(tr -> tr.getHash().equals(reference.getHash())).findFirst();
                            if(optionalReference.isPresent()) {
                                BlockTransactionHash existingReference = optionalReference.get();
                                if(existingReference.getHeight() < reference.getHeight()) {
                                    existingReferences.remove(existingReference);
                                    existingReferences.add(reference);
                                }
                            }
                        }
                    }
                }
            }
        } catch (ElectrumServerRpcException e) {
            throw new ServerException(e.getMessage(), e.getCause());
        } catch (Exception e) {
            throw new ServerException(e);
        }
    }

    public void subscribeWalletNodes(Wallet wallet, Collection<WalletNode> nodes, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap, int startIndex) throws ServerException {
        try {
            Set<String> scriptHashes = new HashSet<>();
            Map<String, String> pathScriptHashes = new LinkedHashMap<>();
            Map<String, WalletNode> pathNodes = new HashMap<>();
            for(WalletNode node : nodes) {
                if(node == null) {
                    log.error("Null node for wallet " + wallet.getFullName() + " subscribing nodes " + nodes + " startIndex " + startIndex, new Throwable());
                }

                if(node != null && node.getIndex() >= startIndex) {
                    String scriptHash = getScriptHash(node);
                    String subscribedStatus = getSubscribedScriptHashStatus(scriptHash);
                    if(subscribedStatus != null) {
                        //Already subscribed, but still need to fetch history from a used node if not previously fetched or present
                        if(!subscribedStatus.equals(retrievedScriptHashes.get(scriptHash)) || !subscribedStatus.equals(getScriptHashStatus(scriptHash, node))) {
                            nodeTransactionMap.put(node, new TreeSet<>());
                        }
                    } else if(!subscribedScriptHashes.containsKey(scriptHash) && scriptHashes.add(scriptHash)) {
                        //Unique script hash we are not yet subscribed to
                        pathScriptHashes.put(node.getDerivationPath(), scriptHash);
                        pathNodes.put(node.getDerivationPath(), node);
                    }
                }
            }

            log.debug("Subscribe to:        " + pathScriptHashes.keySet());

            if(pathScriptHashes.isEmpty()) {
                return;
            }

            Map<String, String> result = electrumServerRpc.subscribeScriptHashes(getTransport(), wallet, pathScriptHashes);

            for(String path : result.keySet()) {
                String status = result.get(path);

                WalletNode node = pathNodes.computeIfAbsent(path, p -> nodes.stream().filter(n -> n.getDerivationPath().equals(p)).findFirst().orElse(null));
                if(node != null) {
                    String scriptHash = getScriptHash(node);

                    //Check if there is history for this script hash, and if the history has changed since last fetched
                    if(status != null && !status.equals(retrievedScriptHashes.get(scriptHash))) {
                        //Set the value for this node to be an empty set to mark it as requiring a get_history RPC call for this wallet
                        nodeTransactionMap.put(node, new TreeSet<>());
                    }

                    updateSubscribedScriptHashStatus(scriptHash, status);
                }
            }
        } catch (ElectrumServerRpcException e) {
            throw new ServerException(e.getMessage(), e.getCause());
        } catch (Exception e) {
            throw new ServerException(e);
        }
    }

    public List<Set<BlockTransactionHash>> getOutputTransactionReferences(Transaction transaction, int indexStart, int indexEnd, List<Set<BlockTransactionHash>> blockTransactionHashes) throws ServerException {
        try {
            Map<String, String> pathScriptHashes = new LinkedHashMap<>();
            for(int i = indexStart; i < transaction.getOutputs().size() && i < indexEnd; i++) {
                if(blockTransactionHashes.get(i) == null) {
                    TransactionOutput output = transaction.getOutputs().get(i);
                    pathScriptHashes.put(Integer.toString(i), getScriptHash(output));
                }
            }

            Map<String, ScriptHashTx[]> result = new HashMap<>();
            if(!pathScriptHashes.isEmpty()) {
                result = electrumServerRpc.getScriptHashHistory(getTransport(), null, pathScriptHashes, false);
            }

            for(String index : result.keySet()) {
                ScriptHashTx[] txes = result.get(index);

                int txBlockHeight = 0;
                Optional<BlockTransactionHash> optionalTxHash = Arrays.stream(txes)
                        .map(ScriptHashTx::getBlockchainTransactionHash)
                        .filter(ref -> ref.getHash().equals(transaction.getTxId()))
                        .findFirst();
                if(optionalTxHash.isPresent()) {
                    txBlockHeight = optionalTxHash.get().getHeight();
                }

                final int minBlockHeight = txBlockHeight;
                Set<BlockTransactionHash> references = Arrays.stream(txes)
                        .map(ScriptHashTx::getBlockchainTransactionHash)
                        .filter(ref -> !ref.getHash().equals(transaction.getTxId()) && ref.getHeight() >= minBlockHeight)
                        .collect(Collectors.toCollection(TreeSet::new));

                blockTransactionHashes.set(Integer.parseInt(index), references);
            }

            return blockTransactionHashes;
        } catch (IllegalStateException e) {
            throw new ServerException(e.getCause());
        } catch (ElectrumServerRpcException e) {
            throw new ServerException(e.getMessage(), e.getCause());
        } catch (Exception e) {
            throw new ServerException(e);
        }
    }

    public void getReferencedTransactions(Wallet wallet, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap) throws ServerException {
        Map<BlockTransactionHash, Transaction> references = new TreeMap<>();
        for(Set<BlockTransactionHash> nodeReferences : nodeTransactionMap.values()) {
            for(BlockTransactionHash nodeReference : nodeReferences) {
                references.put(nodeReference, null);
            }
        }

        for(Iterator<Map.Entry<BlockTransactionHash, Transaction>> iter = references.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<BlockTransactionHash, Transaction> entry = iter.next();
            BlockTransactionHash reference = entry.getKey();
            BlockTransaction blockTransaction = wallet.getWalletTransaction(reference.getHash());
            if(blockTransaction != null) {
                if(reference.getHeight() == blockTransaction.getHeight()) {
                    iter.remove();
                } else {
                    entry.setValue(blockTransaction.getTransaction());
                }
            } else if(broadcastedTransactions.containsKey(reference.getHash())) {
                entry.setValue(broadcastedTransactions.get(reference.getHash()).getTransaction());
            }
        }

        Map<Sha256Hash, BlockTransaction> transactionMap = new HashMap<>();
        if(!references.isEmpty()) {
            Map<Integer, BlockHeader> blockHeaderMap = getBlockHeaders(wallet, references.keySet());
            transactionMap = getTransactions(wallet, references, blockHeaderMap);
        }

        if(!transactionMap.equals(wallet.getTransactions())) {
            wallet.updateTransactions(transactionMap);
            broadcastedTransactions.keySet().removeAll(transactionMap.entrySet().stream().filter(entry -> entry.getValue().getHeight() > 0)
                    .map(Map.Entry::getKey).collect(Collectors.toSet()));
        }
    }

    public Map<Integer, BlockHeader> getBlockHeaders(Wallet wallet, Set<BlockTransactionHash> references) throws ServerException {
        try {
            Map<Integer, BlockHeader> blockHeaderMap = new TreeMap<>();
            Set<Integer> blockHeights = new TreeSet<>();
            for(BlockTransactionHash reference : references) {
                if(reference.getHeight() > 0) {
                    if(retrievedBlockHeaders.containsKey(reference.getHeight())) {
                        blockHeaderMap.put(reference.getHeight(), retrievedBlockHeaders.get(reference.getHeight()));
                    } else {
                        blockHeights.add(reference.getHeight());
                    }
                }
            }

            if(blockHeights.isEmpty()) {
                return blockHeaderMap;
            }

            Map<Integer, String> result = electrumServerRpc.getBlockHeaders(getTransport(), wallet, blockHeights);

            for(Integer height : result.keySet()) {
                byte[] blockHeaderBytes = Utils.hexToBytes(result.get(height));
                BlockHeader blockHeader = new BlockHeader(blockHeaderBytes);
                blockHeaderMap.put(height, blockHeader);
                updateRetrievedBlockHeaders(height, blockHeader);
                blockHeights.remove(height);
            }

            if(!blockHeights.isEmpty()) {
                log.warn("Could not retrieve " + blockHeights.size() + " blocks");
            }

            return blockHeaderMap;
        } catch (IllegalStateException e) {
            throw new ServerException(e.getCause());
        } catch (ElectrumServerRpcException e) {
            throw new ServerException(e.getMessage(), e.getCause());
        } catch (Exception e) {
            throw new ServerException(e);
        }
    }

    public Map<Sha256Hash, BlockTransaction> getTransactions(Wallet wallet, Map<BlockTransactionHash, Transaction> references, Map<Integer, BlockHeader> blockHeaderMap) throws ServerException {
        try {
            Map<Sha256Hash, BlockTransaction> transactionMap = new HashMap<>();
            Set<BlockTransactionHash> checkReferences = new TreeSet<>(references.keySet());

            Set<String> txids = new LinkedHashSet<>(references.size());
            for(BlockTransactionHash reference : references.keySet()) {
                if(references.get(reference) == null) {
                    txids.add(reference.getHashAsString());
                }
            }

            if(!txids.isEmpty()) {
                Map<String, String> result = electrumServerRpc.getTransactions(getTransport(), wallet, txids);

                String strErrorTx = Sha256Hash.ZERO_HASH.toString();
                for(String txid : result.keySet()) {
                    Sha256Hash hash = Sha256Hash.wrap(txid);
                    String strRawTx = result.get(txid);

                    if(strRawTx.equals(strErrorTx)) {
                        transactionMap.put(hash, UNFETCHABLE_BLOCK_TRANSACTION);
                        checkReferences.removeIf(ref -> ref.getHash().equals(hash));
                        continue;
                    }

                    byte[] rawtx = Utils.hexToBytes(strRawTx);
                    Transaction transaction;

                    try {
                        transaction = new Transaction(rawtx);
                    } catch(ProtocolException e) {
                        log.error("Could not parse tx: " + strRawTx);
                        continue;
                    }

                    Optional<BlockTransactionHash> optionalReference = references.keySet().stream().filter(reference -> reference.getHash().equals(hash)).findFirst();
                    if(optionalReference.isEmpty()) {
                        throw new IllegalStateException("Returned transaction " + hash.toString() + " that was not requested");
                    }
                    BlockTransactionHash reference = optionalReference.get();

                    references.put(reference, transaction);
                }
            }

            for(BlockTransactionHash reference : references.keySet()) {
                Transaction transaction = references.get(reference);

                Date blockDate = null;
                if(reference.getHeight() > 0) {
                    BlockHeader blockHeader = blockHeaderMap.get(reference.getHeight());
                    if(blockHeader == null) {
                        transactionMap.put(reference.getHash(), UNFETCHABLE_BLOCK_TRANSACTION);
                        checkReferences.removeIf(ref -> ref.getHash().equals(reference.getHash()));
                        continue;
                    }
                    blockDate = blockHeader.getTimeAsDate();
                }

                BlockTransaction blockchainTransaction = new BlockTransaction(reference.getHash(), reference.getHeight(), blockDate, reference.getFee(), transaction);

                transactionMap.put(reference.getHash(), blockchainTransaction);
                checkReferences.remove(reference);
            }

            if(!checkReferences.isEmpty()) {
                throw new IllegalStateException("Could not retrieve transactions " + checkReferences);
            }

            return transactionMap;
        } catch (IllegalStateException e) {
            throw new ServerException(e.getCause());
        } catch (ElectrumServerRpcException e) {
            throw new ServerException(e.getMessage(), e.getCause());
        } catch (Exception e) {
            throw new ServerException(e);
        }
    }

    public void calculateNodeHistory(Wallet wallet, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap) {
        for(WalletNode node : nodeTransactionMap.keySet()) {
            calculateNodeHistory(wallet, nodeTransactionMap, node);
        }
    }

    public void calculateNodeHistory(Wallet wallet, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap, WalletNode node) {
        Set<BlockTransactionHashIndex> transactionOutputs = new TreeSet<>();

        //First check all provided txes that pay to this node
        Script nodeScript = node.getOutputScript();
        Set<BlockTransactionHash> history = nodeTransactionMap.get(node);
        Map<Sha256Hash, BlockTransactionHash> txHashHistory = new HashMap<>();
        for(BlockTransactionHash reference : history) {
            txHashHistory.put(reference.getHash(), reference);
            BlockTransaction blockTransaction = wallet.getTransactions().get(reference.getHash());
            if(blockTransaction == null) {
                throw new IllegalStateException("Did not retrieve transaction for hash " + reference.getHashAsString());
            } else if(blockTransaction.equals(UNFETCHABLE_BLOCK_TRANSACTION)) {
                throw new IllegalStateException("Could not retrieve transaction for hash " + reference.getHashAsString());
            }
            Transaction transaction = blockTransaction.getTransaction();

            for(int outputIndex = 0; outputIndex < transaction.getOutputs().size(); outputIndex++) {
                TransactionOutput output = transaction.getOutputs().get(outputIndex);
                if (output.getScript().equals(nodeScript)) {
                    BlockTransactionHashIndex receivingTXO = new BlockTransactionHashIndex(reference.getHash(), reference.getHeight(), blockTransaction.getDate(), reference.getFee(), output.getIndex(), output.getValue());
                    transactionOutputs.add(receivingTXO);
                }
            }
        }

        //Then check all provided txes that pay from this node
        for(BlockTransactionHash reference : history) {
            BlockTransaction blockTransaction = wallet.getTransactions().get(reference.getHash());
            if(blockTransaction == null || blockTransaction.equals(UNFETCHABLE_BLOCK_TRANSACTION)) {
                throw new IllegalStateException("Could not retrieve transaction for hash " + reference.getHashAsString());
            }
            Transaction transaction = blockTransaction.getTransaction();

            for(int inputIndex = 0; inputIndex < transaction.getInputs().size(); inputIndex++) {
                TransactionInput input = transaction.getInputs().get(inputIndex);
                Sha256Hash previousHash = input.getOutpoint().getHash();
                BlockTransaction previousTransaction = wallet.getTransactions().get(previousHash);

                if(previousTransaction == null) {
                    //No referenced transaction found, cannot check if spends from wallet
                    //This is fine so long as all referenced transactions have been returned, in which case this refers to a transaction that does not affect this wallet
                    continue;
                } else if(previousTransaction.equals(UNFETCHABLE_BLOCK_TRANSACTION)) {
                    throw new IllegalStateException("Could not retrieve transaction for hash " + reference.getHashAsString());
                }

                BlockTransactionHash spentTxHash = txHashHistory.get(previousHash);
                if(spentTxHash == null) {
                    //No previous transaction history found, cannot check if spends from wallet
                    //This is fine so long as all referenced transactions have been returned, in which case this refers to a transaction that does not affect this wallet node
                    continue;
                }

                TransactionOutput spentOutput = previousTransaction.getTransaction().getOutputs().get((int)input.getOutpoint().getIndex());
                if(spentOutput.getScript().equals(nodeScript)) {
                    BlockTransactionHashIndex spendingTXI = new BlockTransactionHashIndex(reference.getHash(), reference.getHeight(), blockTransaction.getDate(), reference.getFee(), inputIndex, spentOutput.getValue());
                    BlockTransactionHashIndex spentTXO = new BlockTransactionHashIndex(spentTxHash.getHash(), spentTxHash.getHeight(), previousTransaction.getDate(), spentTxHash.getFee(), spentOutput.getIndex(), spentOutput.getValue(), spendingTXI);

                    Optional<BlockTransactionHashIndex> optionalReference = transactionOutputs.stream().filter(receivedTXO -> receivedTXO.getHash().equals(spentTXO.getHash()) && receivedTXO.getIndex() == spentTXO.getIndex()).findFirst();
                    if(optionalReference.isEmpty()) {
                        throw new IllegalStateException("Found spent transaction output " + spentTXO + " but no record of receiving it");
                    }

                    BlockTransactionHashIndex receivedTXO = optionalReference.get();
                    receivedTXO.setSpentBy(spendingTXI);
                }
            }
        }

        if(!transactionOutputs.equals(node.getTransactionOutputs())) {
            node.updateTransactionOutputs(wallet, transactionOutputs);
            copyPostmixLabels(wallet, transactionOutputs);
            copyBadbankLabels(wallet, transactionOutputs);
        }
    }

    public void copyPostmixLabels(Wallet wallet, Set<BlockTransactionHashIndex> newTransactionOutputs) {
        if(wallet.getStandardAccountType() == StandardAccount.WHIRLPOOL_POSTMIX && wallet.getMasterWallet() != null) {
            for(BlockTransactionHashIndex newRef : newTransactionOutputs) {
                BlockTransactionHashIndex prevRef = wallet.getWalletTxos().keySet().stream()
                        .filter(txo -> wallet.getMasterWallet().getUtxoMixData(txo) != null && txo.isSpent() && txo.getSpentBy().getHash().equals(newRef.getHash())).findFirst().orElse(null);
                if(prevRef != null && wallet.getMasterWallet().getUtxoMixData(newRef) != null) {
                    if(newRef.getLabel() == null && prevRef.getLabel() != null) {
                        newRef.setLabel(prevRef.getLabel());
                    }
                }
            }
        }
    }

    public void copyBadbankLabels(Wallet wallet, Set<BlockTransactionHashIndex> newTransactionOutputs) {
        if(wallet.getStandardAccountType() == StandardAccount.WHIRLPOOL_BADBANK && wallet.getMasterWallet() != null) {
            Map<BlockTransactionHashIndex, WalletNode> masterWalletTxos = wallet.getMasterWallet().getWalletTxos();
            for(BlockTransactionHashIndex newRef : newTransactionOutputs) {
                BlockTransactionHashIndex prevRef = masterWalletTxos.keySet().stream()
                        .filter(txo -> txo.isSpent() && txo.getSpentBy().getHash().equals(newRef.getHash()) && txo.getLabel() != null).findFirst().orElse(null);
                if(prevRef != null) {
                    if(newRef.getLabel() == null && prevRef.getLabel() != null) {
                        newRef.setLabel("From " + prevRef.getLabel());
                    }
                }
            }
        }
    }

    public Map<Sha256Hash, BlockTransaction> getReferencedTransactions(Set<Sha256Hash> references, String scriptHash) throws ServerException {
        Set<String> txids = new LinkedHashSet<>(references.size());
        for(Sha256Hash reference : references) {
            txids.add(reference.toString());
        }

        Map<String, VerboseTransaction> result = electrumServerRpc.getVerboseTransactions(getTransport(), txids, scriptHash);

        Map<Sha256Hash, BlockTransaction> transactionMap = new HashMap<>();
        for(String txid : result.keySet()) {
            Sha256Hash hash = Sha256Hash.wrap(txid);
            BlockTransaction blockTransaction = result.get(txid).getBlockTransaction();
            transactionMap.put(hash, blockTransaction);
        }

        return transactionMap;
    }

    public Map<Integer, Double> getFeeEstimates(List<Integer> targetBlocks, boolean useCached) throws ServerException {
        Map<Integer, Double> targetBlocksFeeRatesSats = getDefaultFeeEstimates(targetBlocks);

        FeeRatesSource feeRatesSource = Config.get().getFeeRatesSource();
        feeRatesSource = (feeRatesSource == null ? FeeRatesSource.MEMPOOL_SPACE : feeRatesSource);
        if(!feeRatesSource.isExternal()) {
            targetBlocksFeeRatesSats.putAll(feeRatesSource.getBlockTargetFeeRates(targetBlocksFeeRatesSats));
        } else if(useCached) {
            if(AppServices.getTargetBlockFeeRates() != null) {
                targetBlocksFeeRatesSats.putAll(AppServices.getTargetBlockFeeRates());
            }
        } else if(feeRatesSource.supportsNetwork(Network.get())) {
            targetBlocksFeeRatesSats.putAll(feeRatesSource.getBlockTargetFeeRates(targetBlocksFeeRatesSats));
        }

        return targetBlocksFeeRatesSats;
    }

    public Double getNextBlockMedianFeeRate() {
        FeeRatesSource feeRatesSource = Config.get().getFeeRatesSource();
        feeRatesSource = (feeRatesSource == null ? FeeRatesSource.MEMPOOL_SPACE : feeRatesSource);
        if(feeRatesSource.supportsNetwork(Network.get())) {
            try {
                return feeRatesSource.getNextBlockMedianFeeRate();
            } catch(Exception e) {
                return null;
            }
        }

        return null;
    }

    public Map<Integer, Double> getDefaultFeeEstimates(List<Integer> targetBlocks) throws ServerException {
        try {
            Map<Integer, Double> targetBlocksFeeRatesBtcKb = electrumServerRpc.getFeeEstimates(getTransport(), targetBlocks);

            Map<Integer, Double> targetBlocksFeeRatesSats = new TreeMap<>();
            for(Integer target : targetBlocksFeeRatesBtcKb.keySet()) {
                long minFeeRateSatsKb = (long)(targetBlocksFeeRatesBtcKb.get(target) * Transaction.SATOSHIS_PER_BITCOIN);
                if(minFeeRateSatsKb < 0) {
                    minFeeRateSatsKb = 1000;
                }
                targetBlocksFeeRatesSats.put(target, minFeeRateSatsKb / 1000d);
            }

            return targetBlocksFeeRatesSats;
        } catch(ElectrumServerRpcException e) {
            log.warn(e.getMessage());
            return targetBlocks.stream().collect(Collectors.toMap(java.util.function.Function.identity(), v -> AppServices.getFallbackFeeRate(),
                    (u, v) -> { throw new IllegalStateException("Duplicate target blocks"); },
                    LinkedHashMap::new));
        }
    }

    public Set<MempoolRateSize> getMempoolRateSizes() throws ServerException {
        Map<Double, Long> feeRateHistogram = electrumServerRpc.getFeeRateHistogram(getTransport());
        Set<MempoolRateSize> mempoolRateSizes = new TreeSet<>();
        for(Double fee : feeRateHistogram.keySet()) {
            mempoolRateSizes.add(new MempoolRateSize(fee, feeRateHistogram.get(fee)));
        }

        return mempoolRateSizes;
    }

    public Double getMinimumRelayFee() throws ServerException {
        Double minFeeRateBtcKb = electrumServerRpc.getMinimumRelayFee(getTransport());
        if(minFeeRateBtcKb != null) {
            long minFeeRateSatsKb = (long)(minFeeRateBtcKb * Transaction.SATOSHIS_PER_BITCOIN);
            return minFeeRateSatsKb / 1000d;
        }

        return Transaction.DEFAULT_MIN_RELAY_FEE;
    }

    public Map<Integer, BlockSummary> getRecentBlockSummaryMap() throws ServerException {
        return getBlockSummaryMap(null, null);
    }

    public Map<Integer, BlockSummary> getBlockSummaryMap(Integer height, BlockHeader blockHeader) throws ServerException {
        if(serverCapability.supportsBlockStats()) {
            if(height == null) {
                Integer current = AppServices.getCurrentBlockHeight();
                if(current == null) {
                    return Collections.emptyMap();
                }
                Set<Integer> heights = IntStream.range(current - 1, current + 1).boxed().collect(Collectors.toSet());
                Map<Integer, BlockStats> blockStats = electrumServerRpc.getBlockStats(getTransport(), heights);
                return blockStats.keySet().stream().collect(Collectors.toMap(java.util.function.Function.identity(), v -> blockStats.get(v).toBlockSummary()));
            } else {
                Map<Integer, BlockStats> blockStats = electrumServerRpc.getBlockStats(getTransport(), Set.of(height));
                return blockStats.keySet().stream().collect(Collectors.toMap(java.util.function.Function.identity(), v -> blockStats.get(v).toBlockSummary()));
            }
        }

        FeeRatesSource feeRatesSource = Config.get().getFeeRatesSource();
        feeRatesSource = (feeRatesSource == null ? FeeRatesSource.MEMPOOL_SPACE : feeRatesSource);

        if(feeRatesSource.supportsNetwork(Network.get())) {
            try {
                if(blockHeader == null) {
                    return feeRatesSource.getRecentBlockSummaries();
                } else {
                    Map<Integer, BlockSummary> blockSummaryMap = new HashMap<>();
                    BlockSummary blockSummary = feeRatesSource.getBlockSummary(Sha256Hash.twiceOf(blockHeader.bitcoinSerialize()));
                    if(blockSummary != null && blockSummary.getHeight() != null) {
                        blockSummaryMap.put(blockSummary.getHeight(), blockSummary);
                    }
                    return blockSummaryMap;
                }
            } catch(Exception e) {
                return getServerBlockSummaryMap(height, blockHeader);
            }
        } else {
            return getServerBlockSummaryMap(height, blockHeader);
        }
    }

    private Map<Integer, BlockSummary> getServerBlockSummaryMap(Integer height, BlockHeader blockHeader) throws ServerException {
        if(blockHeader == null || height == null) {
            Integer current = AppServices.getCurrentBlockHeight();
            if(current == null) {
                return Collections.emptyMap();
            }
            Set<BlockTransactionHash> references = IntStream.range(current - 1, current + 1)
                    .mapToObj(i -> new BlockTransaction(null, i, null, null, null)).collect(Collectors.toSet());
            Map<Integer, BlockHeader> blockHeaders = getBlockHeaders(null, references);
            return blockHeaders.keySet().stream()
                    .collect(Collectors.toMap(java.util.function.Function.identity(), v -> new BlockSummary(v, blockHeaders.get(v).getTimeAsDate())));
        } else {
            Map<Integer, BlockSummary> blockSummaryMap = new HashMap<>();
            blockSummaryMap.put(height, new BlockSummary(height, blockHeader.getTimeAsDate()));
            return blockSummaryMap;
        }
    }

    public List<BlockTransaction> getRecentMempoolTransactions() {
        FeeRatesSource feeRatesSource = Config.get().getFeeRatesSource();
        feeRatesSource = (feeRatesSource == null ? FeeRatesSource.MEMPOOL_SPACE : feeRatesSource);

        if(feeRatesSource.supportsNetwork(Network.get())) {
            try {
                List<BlockTransactionHash> recentTransactions = feeRatesSource.getRecentMempoolTransactions();
                Map<BlockTransactionHash, Transaction> setReferences = new HashMap<>();
                setReferences.put(recentTransactions.getFirst(), null);
                if(recentTransactions.size() > 1) {
                    Random random = new Random();
                    int halfSize = recentTransactions.size() / 2;
                    setReferences.put(recentTransactions.get(halfSize == 1 ? 1 : random.nextInt(halfSize) + 1), null);
                }
                Map<Sha256Hash, BlockTransaction> transactions = getTransactions(null, setReferences, Collections.emptyMap());
                return transactions.values().stream().filter(blxTx -> blxTx.getTransaction() != null).toList();
            } catch(Exception e) {
                return Collections.emptyList();
            }
        } else {
            return Collections.emptyList();
        }
    }

    public Sha256Hash broadcastTransaction(Transaction transaction, Long fee) throws ServerException {
        Sha256Hash txid = broadcastTransactionPrivately(transaction);
        if(txid != null) {
            BlockTransaction blkTx = new BlockTransaction(txid, 0, null, fee, transaction);
            broadcastedTransactions.put(txid, blkTx);
        }

        return txid;
    }

    public Sha256Hash broadcastTransactionPrivately(Transaction transaction) throws ServerException {
        //If Tor proxy is configured, try all external broadcast sources in random order before falling back to connected Electrum server
        if(AppServices.isUsingProxy()) {
            List<BroadcastSource> broadcastSources = Arrays.stream(BroadcastSource.values()).filter(src -> src.getSupportedNetworks().contains(Network.get())).collect(Collectors.toList());
            Sha256Hash txid = null;
            for(int i = 1; !broadcastSources.isEmpty(); i++) {
                try {
                    BroadcastSource broadcastSource = broadcastSources.remove(new Random().nextInt(broadcastSources.size()));
                    txid = broadcastSource.broadcastTransaction(transaction);
                    if(Network.get() != Network.MAINNET || i >= MINIMUM_BROADCASTS || broadcastSources.isEmpty()) {
                        return txid;
                    }
                } catch(BroadcastSource.BroadcastException e) {
                    //ignore, already logged
                }
            }

            if(txid != null) {
                return txid;
            }
        }

        return broadcastTransaction(transaction);
    }

    public Sha256Hash broadcastTransaction(Transaction transaction) throws ServerException {
        byte[] rawtxBytes = transaction.bitcoinSerialize();
        String rawtxHex = Utils.bytesToHex(rawtxBytes);

        try {
            String strTxHash = electrumServerRpc.broadcastTransaction(getTransport(), rawtxHex);
            Sha256Hash receivedTxid = Sha256Hash.wrap(strTxHash);
            if(!receivedTxid.equals(transaction.getTxId())) {
                throw new ServerException("Received txid was different (" + receivedTxid + ")");
            }

            return receivedTxid;
        } catch(ElectrumServerRpcException | IllegalStateException e) {
            throw new ServerException(e.getMessage(), e);
        }
    }

    public Set<String> getMempoolScriptHashes(Wallet wallet, Sha256Hash txId, Set<WalletNode> transactionNodes) throws ServerException {
        Map<String, String> pathScriptHashes = new LinkedHashMap<>(transactionNodes.size());
        for(WalletNode node : transactionNodes) {
            pathScriptHashes.put(node.getDerivationPath(), getScriptHash(node));
        }

        Set<String> mempoolScriptHashes = new LinkedHashSet<>();
        Map<String, ScriptHashTx[]> result = electrumServerRpc.getScriptHashHistory(getTransport(), wallet, pathScriptHashes, true);
        for(String path : result.keySet()) {
            ScriptHashTx[] txes = result.get(path);
            if(Arrays.stream(txes).map(ScriptHashTx::getBlockchainTransactionHash).anyMatch(ref -> txId.equals(ref.getHash()) && ref.getHeight() <= 0)) {
                mempoolScriptHashes.add(pathScriptHashes.get(path));
            }
        }

        return mempoolScriptHashes;
    }

    public List<TransactionOutput> getUtxos(Address address) throws ServerException {
        Wallet wallet = new Wallet(address.toString());
        Map<String, String> pathScriptHashes = new HashMap<>();
        pathScriptHashes.put("m/0", getScriptHash(address));
        Map<String, ScriptHashTx[]> historyResult = electrumServerRpc.getScriptHashHistory(getTransport(), wallet, pathScriptHashes, true);
        Set<String> txids = Arrays.stream(historyResult.get("m/0")).map(scriptHashTx -> scriptHashTx.tx_hash).collect(Collectors.toSet());

        Map<String, String> transactionsResult = electrumServerRpc.getTransactions(getTransport(), wallet, txids);
        List<TransactionOutput> transactionOutputs = new ArrayList<>();
        Script outputScript = address.getOutputScript();
        String strErrorTx = Sha256Hash.ZERO_HASH.toString();
        List<Transaction> transactions = new ArrayList<>();
        for(String txid : transactionsResult.keySet()) {
            String strRawTx = transactionsResult.get(txid);

            if(strRawTx.equals(strErrorTx)) {
                continue;
            }

            try {
                Transaction transaction = new Transaction(Utils.hexToBytes(strRawTx));
                for(TransactionOutput txOutput : transaction.getOutputs()) {
                    if(txOutput.getScript().equals(outputScript)) {
                        transactionOutputs.add(txOutput);
                    }
                }
                transactions.add(transaction);
            } catch(ProtocolException e) {
                log.error("Could not parse tx: " + strRawTx);
            }
        }

        for(Transaction transaction : transactions) {
            for(TransactionInput txInput : transaction.getInputs()) {
                transactionOutputs.removeIf(txOutput -> txOutput.getHash().equals(txInput.getOutpoint().getHash()) && txOutput.getIndex() == txInput.getOutpoint().getIndex());
            }
        }

        return transactionOutputs;
    }

    public static Map<String, WalletNode> getAllScriptHashes(Wallet wallet) {
        Map<String, WalletNode> scriptHashes = new HashMap<>();
        for(KeyPurpose keyPurpose : KeyPurpose.DEFAULT_PURPOSES) {
            for(WalletNode childNode : wallet.getNode(keyPurpose).getChildren()) {
                scriptHashes.put(getScriptHash(childNode), childNode);
            }
        }

        return scriptHashes;
    }

    private static TransactionOutput getPrevOutput(Wallet wallet, TransactionInput txInput) {
        try {
            return wallet.getWalletTransaction(txInput.getOutpoint().getHash()).getTransaction().getOutputs().get((int)txInput.getOutpoint().getIndex());
        } catch(Exception e) {
            return null;
        }
    }

    public static String getScriptHash(WalletNode node) {
        byte[] hash = Sha256Hash.hash(node.getOutputScript().getProgram());
        byte[] reversed = Utils.reverseBytes(hash);
        return Utils.bytesToHex(reversed);
    }

    public static String getScriptHash(TransactionOutput output) {
        byte[] hash = Sha256Hash.hash(output.getScript().getProgram());
        byte[] reversed = Utils.reverseBytes(hash);
        return Utils.bytesToHex(reversed);
    }

    public static String getScriptHash(Address address) {
        byte[] hash = Sha256Hash.hash(address.getOutputScript().getProgram());
        byte[] reversed = Utils.reverseBytes(hash);
        return Utils.bytesToHex(reversed);
    }

    public static Map<String, List<String>> getSubscribedScriptHashes() {
        return subscribedScriptHashes;
    }

    public static String getSubscribedScriptHashStatus(String scriptHash) {
        List<String> existingStatuses = subscribedScriptHashes.get(scriptHash);
        if(existingStatuses != null && !existingStatuses.isEmpty()) {
            return existingStatuses.get(existingStatuses.size() - 1);
        }

        return null;
    }

    public static void updateSubscribedScriptHashStatus(String scriptHash, String status) {
        List<String> existingStatuses = subscribedScriptHashes.computeIfAbsent(scriptHash, k -> new ArrayList<>());
        existingStatuses.add(status);
    }

    public static void updateRetrievedBlockHeaders(Integer blockHeight, BlockHeader blockHeader) {
        retrievedBlockHeaders.put(blockHeight, blockHeader);
    }

    public static ServerCapability getServerCapability(List<String> serverVersion) {
        if(!serverVersion.isEmpty()) {
            String server = serverVersion.getFirst().toLowerCase(Locale.ROOT);
            if(server.contains("electrumx")) {
                return new ServerCapability(true, true);
            }

            if(server.startsWith("cormorant")) {
                return new ServerCapability(true, false, true, false);
            }

            if(server.startsWith("electrs/")) {
                String electrsVersion = server.substring("electrs/".length());
                int dashIndex = electrsVersion.indexOf('-');
                if(dashIndex > -1) {
                    electrsVersion = electrsVersion.substring(0, dashIndex);
                }
                try {
                    Version version = new Version(electrsVersion);
                    if(version.compareTo(ELECTRS_MIN_BATCHING_VERSION) >= 0) {
                        return new ServerCapability(true, true);
                    }
                } catch(Exception e) {
                    //ignore
                }
            }

            if(server.startsWith("fulcrum")) {
                String fulcrumVersion = server.substring("fulcrum".length()).trim();
                int dashIndex = fulcrumVersion.indexOf('-');
                if(dashIndex > -1) {
                    fulcrumVersion = fulcrumVersion.substring(0, dashIndex);
                }
                try {
                    Version version = new Version(fulcrumVersion);
                    if(version.compareTo(FULCRUM_MIN_BATCHING_VERSION) >= 0) {
                        return new ServerCapability(true, true);
                    }
                } catch(Exception e) {
                    //ignore
                }
            }

            if(server.startsWith("mempool-electrs")) {
                String mempoolElectrsVersion = server.substring("mempool-electrs".length()).trim();
                int dashIndex = mempoolElectrsVersion.indexOf('-');
                String mempoolElectrsSuffix = "";
                if(dashIndex > -1) {
                    mempoolElectrsSuffix = mempoolElectrsVersion.substring(dashIndex);
                    mempoolElectrsVersion = mempoolElectrsVersion.substring(0, dashIndex);
                }
                try {
                    Version version = new Version(mempoolElectrsVersion);
                    if(version.compareTo(MEMPOOL_ELECTRS_MIN_BATCHING_VERSION) > 0 ||
                            (version.compareTo(MEMPOOL_ELECTRS_MIN_BATCHING_VERSION) == 0 && (!mempoolElectrsSuffix.contains("dev") || mempoolElectrsSuffix.contains("dev-249848d")))) {
                        return new ServerCapability(true, 25, false);
                    }
                } catch(Exception e) {
                    //ignore
                }
            }

            if(server.startsWith("electrumpersonalserver")) {
                return new ServerCapability(false, false);
            }
        }

        return new ServerCapability(false, true);
    }

    public static class ServerVersionService extends Service<List<String>> {
        @Override
        protected Task<List<String>> createTask() {
            return new Task<List<String>>() {
                protected List<String> call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();
                    return electrumServer.getServerVersion();
                }
            };
        }
    }

    public static class ServerBannerService extends Service<String> {
        @Override
        protected Task<String> createTask() {
            return new Task<>() {
                protected String call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();
                    return electrumServer.getServerBanner();
                }
            };
        }
    }

    public static class ConnectionService extends ScheduledService<FeeRatesUpdatedEvent> implements Thread.UncaughtExceptionHandler {
        private static final int FEE_RATES_PERIOD = 30 * 1000;

        private final boolean subscribe;
        private boolean firstCall = true;
        private Thread reader;
        private long feeRatesRetrievedAt;
        private final Bwt bwt = new Bwt();
        private final ReentrantLock bwtStartLock = new ReentrantLock();
        private final Condition bwtStartCondition = bwtStartLock.newCondition();
        private Throwable bwtStartException;
        private boolean shutdown;

        public ConnectionService() {
            this(true);
        }

        public ConnectionService(boolean subscribe) {
            this.subscribe = subscribe;
        }

        @Override
        protected Task<FeeRatesUpdatedEvent> createTask() {
            return new Task<>() {
                protected FeeRatesUpdatedEvent call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();

                    if(Config.get().getServerType() == ServerType.BITCOIN_CORE) {
                        try {
                            if(Config.get().isUseLegacyCoreWallet()) {
                                throw new CormorantBitcoindException("Legacy wallet configured");
                            }
                            if(ElectrumServer.cormorant == null) {
                                ElectrumServer.cormorant = new Cormorant(subscribe);
                                ElectrumServer.coreElectrumServer = cormorant.start();
                            }
                        } catch(CormorantBitcoindException e) {
                            ElectrumServer.cormorant = null;
                            log.debug("Cannot start cormorant: " + e.getMessage() + ". Starting BWT...");

                            Bwt.initialize();

                            if(!bwt.isRunning()) {
                                Bwt.ConnectionService bwtConnectionService = bwt.getConnectionService(subscribe);
                                bwtStartException = null;
                                bwtConnectionService.setOnFailed(workerStateEvent -> {
                                    log.error("Failed to start BWT", workerStateEvent.getSource().getException());
                                    bwtStartException = workerStateEvent.getSource().getException();
                                    try {
                                        bwtStartLock.lock();
                                        bwtStartCondition.signal();
                                    } finally {
                                        bwtStartLock.unlock();
                                    }
                                });
                                Platform.runLater(bwtConnectionService::start);

                                try {
                                    bwtStartLock.lock();
                                    bwtStartCondition.await();

                                    if(!bwt.isReady()) {
                                        if(bwtStartException != null) {
                                            Matcher walletLoadingMatcher = RPC_WALLET_LOADING_PATTERN.matcher(bwtStartException.getMessage());
                                            if(bwtStartException.getMessage().contains("Wallet file not specified")) {
                                                throw new ServerException("Bitcoin Core requires Multi-Wallet to be enabled in the Server Settings");
                                            } else if(bwtStartException.getMessage().contains("Upgrade Bitcoin Core to v24 or later for Taproot wallet support")) {
                                                throw new ServerException(bwtStartException.getMessage());
                                            } else if(bwtStartException.getMessage().contains("Wallet file verification failed. Refusing to load database.")) {
                                                throw new ServerException("Bitcoin Core wallet file verification failed. Try restarting Bitcoin Core.");
                                            } else if(bwtStartException.getMessage().contains("This error could be caused by pruning or data corruption")) {
                                                throw new ServerException("Scanning failed. Bitcoin Core is pruned to a date after the wallet birthday.");
                                            } else if(walletLoadingMatcher.matches() && walletLoadingMatcher.group(1) != null) {
                                                throw new ServerException(walletLoadingMatcher.group(1));
                                            }
                                        }

                                        throw new ServerException("Check if Bitcoin Core is running, and the authentication details are correct.");
                                    }
                                } catch(InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    return null;
                                } finally {
                                    bwtStartLock.unlock();
                                }
                            }
                        }
                    }

                    if(firstCall) {
                        electrumServer.connect();

                        reader = new Thread(new ReadRunnable(), "ElectrumServerReadThread");
                        reader.setDaemon(true);
                        reader.setUncaughtExceptionHandler(ConnectionService.this);
                        reader.start();

                        //Start with simple RPC for maximum compatibility
                        electrumServerRpc = new SimpleElectrumServerRpc();

                        List<String> serverVersion = electrumServer.getServerVersion();
                        firstCall = false;

                        //If electrumx is detected, we can upgrade to batched RPC. Electrs/EPS do not support batching.
                        serverCapability = getServerCapability(serverVersion);
                        if(serverCapability.supportsBatching()) {
                            log.debug("Upgrading to batched JSON-RPC");
                            electrumServerRpc = new BatchedElectrumServerRpc(electrumServerRpc.getIdCounterValue(), serverCapability.getMaxTargetBlocks());
                        }

                        BlockHeaderTip tip;
                        if(subscribe) {
                            tip = electrumServer.subscribeBlockHeaders();
                            subscribedScriptHashes.clear();
                        } else {
                            tip = new BlockHeaderTip();
                        }

                        String banner = electrumServer.getServerBanner();

                        Map<Integer, Double> blockTargetFeeRates = electrumServer.getFeeEstimates(AppServices.TARGET_BLOCKS_RANGE, true);
                        Set<MempoolRateSize> mempoolRateSizes = electrumServer.getMempoolRateSizes();
                        feeRatesRetrievedAt = System.currentTimeMillis();

                        Double minimumRelayFeeRate = electrumServer.getMinimumRelayFee();
                        for(Integer blockTarget : blockTargetFeeRates.keySet()) {
                            blockTargetFeeRates.computeIfPresent(blockTarget, (blocks, feeRate) -> feeRate < minimumRelayFeeRate ? minimumRelayFeeRate : feeRate);
                        }

                        return new ConnectionEvent(serverVersion, banner, tip.height, tip.getBlockHeader(), blockTargetFeeRates, mempoolRateSizes, minimumRelayFeeRate);
                    } else {
                        if(reader.isAlive()) {
                            electrumServer.ping();

                            long elapsed = System.currentTimeMillis() - feeRatesRetrievedAt;
                            if(elapsed > FEE_RATES_PERIOD) {
                                Map<Integer, Double> blockTargetFeeRates = electrumServer.getFeeEstimates(AppServices.TARGET_BLOCKS_RANGE, false);
                                Set<MempoolRateSize> mempoolRateSizes = electrumServer.getMempoolRateSizes();
                                Double nextBlockMedianFeeRate = electrumServer.getNextBlockMedianFeeRate();
                                feeRatesRetrievedAt = System.currentTimeMillis();
                                return new FeeRatesUpdatedEvent(blockTargetFeeRates, mempoolRateSizes, nextBlockMedianFeeRate);
                            }
                        } else {
                            closeConnection();
                        }
                    }

                    return null;
                }
            };
        }

        public void closeConnection() {
            try {
                closeActiveConnection();
                shutdown();
                firstCall = true;
            } catch (ServerException e) {
                log.error("Error closing connection", e);
            }
        }

        public boolean isConnecting() {
            return isRunning() && firstCall && !shutdown && (Config.get().getServerType() != ServerType.BITCOIN_CORE || (cormorant != null && !cormorant.isRunning()) || (bwt.isRunning() && !bwt.isReady()));
        }

        public boolean isConnectionRunning() {
            return isRunning() && (Config.get().getServerType() != ServerType.BITCOIN_CORE || bwt.isRunning());
        }

        public boolean isConnected() {
            return isRunning() && !firstCall && (Config.get().getServerType() != ServerType.BITCOIN_CORE || (cormorant != null && cormorant.isRunning()) || (bwt.isRunning() && bwt.isReady()));
        }

        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean cancel() {
            try {
                closeActiveConnection();
                shutdown();
            } catch (ServerException e) {
                log.error("Error closing connection", e);
            }

            return super.cancel();
        }

        private void shutdown() {
            shutdown = true;
            if(reader != null && reader.isAlive()) {
                reader.interrupt();
            }

            if(ElectrumServer.cormorant != null) {
                ElectrumServer.cormorant.stop();
                ElectrumServer.cormorant = null;
                ElectrumServer.coreElectrumServer = null;
            }

            if(Config.get().getServerType() == ServerType.BITCOIN_CORE && bwt.isRunning()) {
                Bwt.DisconnectionService disconnectionService = bwt.getDisconnectionService();
                disconnectionService.setOnSucceeded(workerStateEvent -> {
                    ElectrumServer.coreElectrumServer = null;
                    if(subscribe) {
                        EventManager.get().post(new BwtShutdownEvent());
                    }
                });
                disconnectionService.setOnFailed(workerStateEvent -> {
                    log.error("Failed to stop BWT", workerStateEvent.getSource().getException());
                });
                disconnectionService.start();
            } else if(subscribe) {
                Platform.runLater(() -> EventManager.get().post(new DisconnectionEvent()));
            }
        }

        @Override
        public void reset() {
            super.reset();
            firstCall = true;
            shutdown = false;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            log.error("Uncaught error in ConnectionService", e);
        }

        @Subscribe
        public void bwtElectrumReadyStatus(BwtElectrumReadyStatusEvent event) {
            if(this.isRunning()) {
                ElectrumServer.coreElectrumServer = new Server(Protocol.TCP.toUrlString(HostAndPort.fromString(event.getElectrumAddr())));
            }
        }

        @Subscribe
        public void bwtReadyStatus(BwtReadyStatusEvent event) {
            if(this.isRunning()) {
                try {
                    bwtStartLock.lock();
                    bwtStartCondition.signal();
                } finally {
                    bwtStartLock.unlock();
                }
            }
        }

        @Subscribe
        public void bwtShutdown(BwtShutdownEvent event) {
            try {
                bwtStartLock.lock();
                bwtStartCondition.signal();
            } finally {
                bwtStartLock.unlock();
            }
        }

        @Subscribe
        public void mempoolEntriesInitialized(MempoolEntriesInitializedEvent event) throws ServerException {
            ElectrumServer electrumServer = new ElectrumServer();
            Set<MempoolRateSize> mempoolRateSizes = electrumServer.getMempoolRateSizes();
            EventManager.get().post(new MempoolRateSizesUpdatedEvent(mempoolRateSizes));
        }

        @Subscribe
        public void walletNodeHistoryChanged(WalletNodeHistoryChangedEvent event) {
            String status = broadcastRecent.remove(event.getScriptHash());
            if(status != null && status.equals(event.getStatus())) {
                Map<String, String> subscribeScriptHashes = new HashMap<>();
                Random random = new Random();
                int subscriptions = random.nextInt(2) + 1;
                for(int i = 0; i < subscriptions; i++) {
                    byte[] randomScriptHashBytes = new byte[32];
                    random.nextBytes(randomScriptHashBytes);
                    String randomScriptHash = Utils.bytesToHex(randomScriptHashBytes);
                    if(!subscribedScriptHashes.containsKey(randomScriptHash)) {
                        subscribeScriptHashes.put("m/" + subscribeScriptHashes.size(), randomScriptHash);
                    }
                }

                try {
                    electrumServerRpc.subscribeScriptHashes(transport, null, subscribeScriptHashes);
                    subscribeScriptHashes.values().forEach(scriptHash -> subscribedRecent.put(scriptHash, AppServices.getCurrentBlockHeight()));
                } catch(ElectrumServerRpcException e) {
                    log.debug("Error subscribing to recent mempool transaction outputs", e);
                }
            }
        }
    }

    public static class ReadRunnable implements Runnable {
        @Override
        public void run() {
            try {
                TcpTransport tcpTransport = (TcpTransport)getTransport();
                tcpTransport.readInputLoop();
            } catch(ServerException e) {
                //Only debug logging here as the exception has been passed on to the ConnectionService thread via TcpTransport
                log.debug("Read thread terminated", e);
            }
        }
    }

    private static class WalletLock {
        public boolean initialized;
    }

    public static class TransactionHistoryService extends Service<Boolean> {
        private final Wallet mainWallet;
        private final List<Wallet> filterToWallets;
        private final Set<WalletNode> filterToNodes;
        private final static Map<Integer, WalletLock> walletLocks = Collections.synchronizedMap(new HashMap<>());

        public TransactionHistoryService(Wallet wallet) {
            this.mainWallet = wallet;
            this.filterToWallets = null;
            this.filterToNodes = null;
        }

        public TransactionHistoryService(Wallet mainWallet, List<Wallet> filterToWallets, Set<WalletNode> filterToNodes) {
            this.mainWallet = mainWallet;
            this.filterToWallets = filterToWallets;
            this.filterToNodes = filterToNodes;
        }

        @Override
        protected Task<Boolean> createTask() {
            return new Task<>() {
                protected Boolean call() throws ServerException {
                    if(ElectrumServer.cormorant != null) {
                        if(!ElectrumServer.cormorant.checkWalletImport(mainWallet)) {
                            return true;
                        }
                    }

                    boolean historyFetched = getTransactionHistory(mainWallet);
                    for(Wallet childWallet : new ArrayList<>(mainWallet.getChildWallets())) {
                        if(childWallet.isNested()) {
                            historyFetched |= getTransactionHistory(childWallet);
                        }
                    }

                    return historyFetched;
                }
            };
        }

        private boolean getTransactionHistory(Wallet wallet) throws ServerException {
            if(filterToWallets != null && !filterToWallets.contains(wallet)) {
                return false;
            }

            Set<WalletNode> nodes = (filterToNodes == null ? null : filterToNodes.stream().filter(node -> node.getWallet().equals(wallet)).collect(Collectors.toSet()));
            if(filterToNodes != null && nodes.isEmpty()) {
                return false;
            }

            WalletLock walletLock = walletLocks.computeIfAbsent(wallet.hashCode(), w -> new WalletLock());
            synchronized(walletLock) {
                if(!walletLock.initialized) {
                    addCalculatedScriptHashes(wallet);
                    walletLock.initialized = true;
                }

                if(isConnected()) {
                    ElectrumServer electrumServer = new ElectrumServer();

                    Map<String, String> previousScriptHashes = getCalculatedScriptHashes(wallet);
                    Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap = (nodes == null ? electrumServer.getHistory(wallet) : electrumServer.getHistory(wallet, nodes));
                    electrumServer.getReferencedTransactions(wallet, nodeTransactionMap);
                    electrumServer.calculateNodeHistory(wallet, nodeTransactionMap);

                    //Add all of the script hashes we have now fetched the history for so we don't need to fetch again until the script hash status changes
                    Set<WalletNode> updatedNodes = new HashSet<>();
                    Map<WalletNode, Set<BlockTransactionHashIndex>> walletNodes = wallet.getWalletNodes();
                    for(WalletNode node : (nodes == null ? walletNodes.keySet() : nodes)) {
                        String scriptHash = getScriptHash(node);
                        String subscribedStatus = getSubscribedScriptHashStatus(scriptHash);
                        if(!Objects.equals(subscribedStatus, retrievedScriptHashes.get(scriptHash))) {
                            updatedNodes.add(node);
                        }
                        retrievedScriptHashes.put(scriptHash, subscribedStatus);
                    }

                    //If wallet was not empty, check if all used updated nodes have changed history
                    if(nodes == null && previousScriptHashes.values().stream().anyMatch(Objects::nonNull)) {
                        if(!updatedNodes.isEmpty()
                                && updatedNodes.equals(walletNodes.entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).map(Map.Entry::getKey).collect(Collectors.toSet()))
                                && !sameHeightTxioScriptHashes.containsAll(updatedNodes.stream().map(ElectrumServer::getScriptHash).collect(Collectors.toSet()))) {
                            //All used nodes on a non-empty wallet have changed history. Abort and trigger a full refresh.
                            log.info("All used nodes on a non-empty wallet have changed history. Triggering a full wallet refresh.");
                            throw new AllHistoryChangedException();
                        }
                    }

                    //Clear transaction outputs for nodes that have no history - this is useful when a transaction is replaced in the mempool
                    if(nodes != null) {
                        for(WalletNode node : nodes) {
                            String scriptHash = getScriptHash(node);
                            if(retrievedScriptHashes.get(scriptHash) == null && !node.getTransactionOutputs().isEmpty()) {
                                log.debug("Clearing transaction history for " + node);
                                node.getTransactionOutputs().clear();
                            }
                        }
                    }

                    return true;
                }

                return false;
            }
        }
    }

    public static class TransactionMempoolService extends ScheduledService<Set<String>> {
        private final Wallet wallet;
        private final Sha256Hash txId;
        private final Set<WalletNode> nodes;
        private final IntegerProperty iterationCount = new SimpleIntegerProperty(0);
        private boolean cancelled;

        public TransactionMempoolService(Wallet wallet, Sha256Hash txId, Set<WalletNode> nodes) {
            this.wallet = wallet;
            this.txId = txId;
            this.nodes = nodes;
        }

        public int getIterationCount() {
            return iterationCount.get();
        }

        public IntegerProperty iterationCountProperty() {
            return iterationCount;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void start() {
            this.cancelled = false;
            super.start();
        }

        @Override
        public boolean cancel() {
            this.cancelled = true;
            return super.cancel();
        }

        @Override
        protected Task<Set<String>> createTask() {
            return new Task<>() {
                protected Set<String> call() throws ServerException {
                    if(ElectrumServer.cormorant != null) {
                        if(!ElectrumServer.cormorant.checkWalletImport(wallet)) {
                            return Collections.emptySet();
                        }
                    }

                    iterationCount.set(iterationCount.get() + 1);
                    ElectrumServer electrumServer = new ElectrumServer();
                    return electrumServer.getMempoolScriptHashes(wallet, txId, nodes);
                }
            };
        }
    }

    public static class TransactionReferenceService extends Service<Map<Sha256Hash, BlockTransaction>> {
        private final Set<Sha256Hash> references;
        private String scriptHash;

        public TransactionReferenceService(Transaction transaction) {
            references = new HashSet<>();
            references.add(transaction.getTxId());
            for(TransactionInput input : transaction.getInputs()) {
                references.add(input.getOutpoint().getHash());
            }
        }

        public TransactionReferenceService(Set<Sha256Hash> references, String scriptHash) {
            this(references);
            this.scriptHash = scriptHash;
        }

        public TransactionReferenceService(Set<Sha256Hash> references) {
            this.references = references;
        }

        @Override
        protected Task<Map<Sha256Hash, BlockTransaction>> createTask() {
            return new Task<>() {
                protected Map<Sha256Hash, BlockTransaction> call() throws ServerException {
                    Map<Sha256Hash, BlockTransaction> transactionMap = new HashMap<>();
                    for(Sha256Hash ref : references) {
                        if(retrievedTransactions.containsKey(ref)) {
                            transactionMap.put(ref, retrievedTransactions.get(ref));
                        }
                    }

                    Set<Sha256Hash> fetchReferences = new HashSet<>(references);
                    fetchReferences.removeAll(transactionMap.keySet());

                    if(!fetchReferences.isEmpty()) {
                        ElectrumServer electrumServer = new ElectrumServer();
                        Map<Sha256Hash, BlockTransaction> fetchedTransactions = electrumServer.getReferencedTransactions(fetchReferences, scriptHash);
                        transactionMap.putAll(fetchedTransactions);

                        for(Map.Entry<Sha256Hash, BlockTransaction> fetchedEntry : fetchedTransactions.entrySet()) {
                            if(fetchedEntry.getValue() != null && !Sha256Hash.ZERO_HASH.equals(fetchedEntry.getValue().getBlockHash()) &&
                                    AppServices.getCurrentBlockHeight() != null && fetchedEntry.getValue().getConfirmations(AppServices.getCurrentBlockHeight()) >= BlockTransactionHash.BLOCKS_TO_CONFIRM) {
                                retrievedTransactions.put(fetchedEntry.getKey(), fetchedEntry.getValue());
                            }
                        }
                    }

                    return transactionMap;
                }
            };
        }
    }

    public static class TransactionOutputsReferenceService extends Service<List<BlockTransaction>> {
        private final Transaction transaction;
        private final int indexStart;
        private final int indexEnd;
        private final List<Set<BlockTransactionHash>> blockTransactionHashes;
        private final Map<Sha256Hash, BlockTransaction> transactionMap;

        public TransactionOutputsReferenceService(Transaction transaction, int indexStart, int indexEnd) {
            this.transaction = transaction;
            this.indexStart = Math.min(transaction.getOutputs().size(), indexStart);
            this.indexEnd = Math.min(transaction.getOutputs().size(), indexEnd);
            this.blockTransactionHashes = new ArrayList<>(transaction.getOutputs().size());
            for(int i = 0; i < transaction.getOutputs().size(); i++) {
                blockTransactionHashes.add(null);
            }
            this.transactionMap = new HashMap<>();
        }

        public TransactionOutputsReferenceService(Transaction transaction, int indexStart, int indexEnd, List<Set<BlockTransactionHash>> blockTransactionHashes, Map<Sha256Hash, BlockTransaction> transactionMap) {
            this.transaction = transaction;
            this.indexStart = Math.min(transaction.getOutputs().size(), indexStart);
            this.indexEnd = Math.min(transaction.getOutputs().size(), indexEnd);
            this.blockTransactionHashes = blockTransactionHashes;
            this.transactionMap = transactionMap;
        }

        @Override
        protected Task<List<BlockTransaction>> createTask() {
            return new Task<>() {
                protected List<BlockTransaction> call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();
                    List<Set<BlockTransactionHash>> outputTransactionReferences = electrumServer.getOutputTransactionReferences(transaction, indexStart, indexEnd, blockTransactionHashes);

                    Map<BlockTransactionHash, Transaction> setReferences = new HashMap<>();
                    for(Set<BlockTransactionHash> outputReferences : outputTransactionReferences) {
                        if(outputReferences != null) {
                            for(BlockTransactionHash outputReference : outputReferences) {
                                setReferences.put(outputReference, null);
                            }
                        }
                    }
                    setReferences.remove(null);
                    setReferences.remove(UNFETCHABLE_BLOCK_TRANSACTION);
                    setReferences.keySet().removeIf(ref -> transactionMap.get(ref.getHash()) != null);

                    List<BlockTransaction> blockTransactions = new ArrayList<>(transaction.getOutputs().size());
                    for(int i = 0; i < transaction.getOutputs().size(); i++) {
                        blockTransactions.add(null);
                    }

                    if(!setReferences.isEmpty()) {
                        Map<Integer, BlockHeader> blockHeaderMap = electrumServer.getBlockHeaders(null, setReferences.keySet());
                        transactionMap.putAll(electrumServer.getTransactions(null, setReferences, blockHeaderMap));
                    }

                    for(int i = 0; i < outputTransactionReferences.size(); i++) {
                        Set<BlockTransactionHash> outputReferences = outputTransactionReferences.get(i);
                        if(outputReferences != null) {
                            for(BlockTransactionHash reference : outputReferences) {
                                if(reference == UNFETCHABLE_BLOCK_TRANSACTION) {
                                    if(blockTransactions.get(i) == null) {
                                        blockTransactions.set(i, UNFETCHABLE_BLOCK_TRANSACTION);
                                    }
                                } else {
                                    BlockTransaction blockTransaction = transactionMap.get(reference.getHash());
                                    if(blockTransaction.equals(UNFETCHABLE_BLOCK_TRANSACTION)) {
                                        if(blockTransactions.get(i) == null) {
                                            blockTransactions.set(i, UNFETCHABLE_BLOCK_TRANSACTION);
                                        }
                                    } else {
                                        for(TransactionInput input : blockTransaction.getTransaction().getInputs()) {
                                            if(input.getOutpoint().getHash().equals(transaction.getTxId()) && input.getOutpoint().getIndex() == i) {
                                                BlockTransaction previousTx = blockTransactions.set(i, blockTransaction);
                                                if(previousTx != null && !previousTx.equals(UNFETCHABLE_BLOCK_TRANSACTION)) {
                                                    throw new IllegalStateException("Double spend detected for output #" + i + " on hash " + reference.getHash());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    return blockTransactions;
                }
            };
        }
    }

    public static class BroadcastTransactionService extends Service<Sha256Hash> {
        private final Transaction transaction;
        private final Long fee;

        public BroadcastTransactionService(Transaction transaction, Long fee) {
            this.transaction = transaction;
            this.fee = fee;
        }

        @Override
        protected Task<Sha256Hash> createTask() {
            return new Task<>() {
                protected Sha256Hash call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();
                    return electrumServer.broadcastTransaction(transaction, fee);
                }
            };
        }
    }

    public static class FeeRatesService extends Service<FeeRatesUpdatedEvent> {
        @Override
        protected Task<FeeRatesUpdatedEvent> createTask() {
            return new Task<>() {
                protected FeeRatesUpdatedEvent call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();
                    Map<Integer, Double> blockTargetFeeRates = electrumServer.getFeeEstimates(AppServices.TARGET_BLOCKS_RANGE, false);
                    Double nextBlockMedianFeeRate = electrumServer.getNextBlockMedianFeeRate();
                    return new FeeRatesUpdatedEvent(blockTargetFeeRates, null, nextBlockMedianFeeRate);
                }
            };
        }
    }

    public static class BlockSummaryService extends Service<BlockSummaryEvent> {
        private final List<NewBlockEvent> newBlockEvents;

        public BlockSummaryService(List<NewBlockEvent> newBlockEvents) {
            this.newBlockEvents = newBlockEvents;
        }

        @Override
        protected Task<BlockSummaryEvent> createTask() {
            return new Task<>() {
                protected BlockSummaryEvent call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();
                    Map<Integer, BlockSummary> blockSummaryMap = new LinkedHashMap<>();

                    int maxHeight = AppServices.getBlockSummaries().keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
                    int startHeight = newBlockEvents.stream().mapToInt(NewBlockEvent::getHeight).min().orElse(0);
                    int endHeight = newBlockEvents.stream().mapToInt(NewBlockEvent::getHeight).max().orElse(0);
                    int totalBlocks = Math.max(0, endHeight - maxHeight);

                    if(startHeight == 0 || totalBlocks > 1 || startHeight > maxHeight + 1) {
                        if(isBlockstorm(totalBlocks)) {
                            int start = Math.max(maxHeight + 1, endHeight - 15);
                            for(int height = start; height <= endHeight; height++) {
                                blockSummaryMap.put(height, new BlockSummary(height, new Date(), 1.0d, 0, 0));
                            }
                        } else {
                            blockSummaryMap.putAll(electrumServer.getRecentBlockSummaryMap());
                        }
                    }

                    List<NewBlockEvent> events = new ArrayList<>(newBlockEvents);
                    events.removeIf(event -> blockSummaryMap.containsKey(event.getHeight()));
                    if(!events.isEmpty()) {
                        for(NewBlockEvent event : newBlockEvents) {
                            blockSummaryMap.putAll(electrumServer.getBlockSummaryMap(event.getHeight(), event.getBlockHeader()));
                        }
                    }

                    Config config = Config.get();
                    if(!isBlockstorm(totalBlocks) && !AppServices.isUsingProxy() && config.getServer().getProtocol().equals(Protocol.SSL)
                            && (config.getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER || config.getServerType() == ServerType.ELECTRUM_SERVER)) {
                        subscribeRecent(electrumServer, AppServices.getCurrentBlockHeight() == null ? endHeight : AppServices.getCurrentBlockHeight());
                    }

                    Double nextBlockMedianFeeRate = null;
                    if(!isBlockstorm(totalBlocks)) {
                        nextBlockMedianFeeRate = electrumServer.getNextBlockMedianFeeRate();
                    }
                    return new BlockSummaryEvent(blockSummaryMap, nextBlockMedianFeeRate);
                }
            };
        }

        private boolean isBlockstorm(int totalBlocks) {
            return Network.get() != Network.MAINNET && totalBlocks > 2;
        }

        private void subscribeRecent(ElectrumServer electrumServer, int currentHeight) {
            Set<String> unsubscribeScriptHashes = subscribedRecent.entrySet().stream().filter(entry -> entry.getValue() == null || entry.getValue() <= currentHeight - 3)
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            unsubscribeScriptHashes.removeIf(subscribedScriptHashes::containsKey);
            if(!unsubscribeScriptHashes.isEmpty() && serverCapability.supportsUnsubscribe()) {
                electrumServerRpc.unsubscribeScriptHashes(transport, unsubscribeScriptHashes);
            }
            subscribedRecent.keySet().removeAll(unsubscribeScriptHashes);
            broadcastRecent.keySet().removeAll(unsubscribeScriptHashes);

            Map<String, String> subscribeScriptHashes = new HashMap<>();
            List<BlockTransaction> recentTransactions = electrumServer.getRecentMempoolTransactions();
            for(BlockTransaction blkTx : recentTransactions) {
                for(int i = 0; i < blkTx.getTransaction().getOutputs().size(); i++) {
                    TransactionOutput txOutput = blkTx.getTransaction().getOutputs().get(i);
                    String scriptHash = getScriptHash(txOutput);
                    if(!subscribedScriptHashes.containsKey(scriptHash)) {
                        subscribeScriptHashes.put("m/" + subscribeScriptHashes.size(), scriptHash);
                    }
                    if(Math.random() < 0.1d) {
                        break;
                    }
                }
            }

            if(!subscribeScriptHashes.isEmpty()) {
                Random random = new Random();
                int additionalRandomScriptHashes = random.nextInt(8);
                for(int i = 0; i < additionalRandomScriptHashes; i++) {
                    byte[] randomScriptHashBytes = new byte[32];
                    random.nextBytes(randomScriptHashBytes);
                    String randomScriptHash = Utils.bytesToHex(randomScriptHashBytes);
                    if(!subscribedScriptHashes.containsKey(randomScriptHash)) {
                        subscribeScriptHashes.put("m/" + subscribeScriptHashes.size(), randomScriptHash);
                    }
                }

                try {
                    electrumServerRpc.subscribeScriptHashes(transport, null, subscribeScriptHashes);
                    subscribeScriptHashes.values().forEach(scriptHash -> subscribedRecent.put(scriptHash, currentHeight));
                } catch(ElectrumServerRpcException e) {
                    log.debug("Error subscribing to recent mempool transactions", e);
                }
            }

            if(!recentTransactions.isEmpty()) {
                broadcastRecent(electrumServer, recentTransactions);
            }
        }

        private void broadcastRecent(ElectrumServer electrumServer, List<BlockTransaction> recentTransactions) {
            ScheduledService<Void> broadcastService = new ScheduledService<>() {
                @Override
                protected Task<Void> createTask() {
                    return new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            if(!recentTransactions.isEmpty()) {
                                Random random = new Random();
                                if(random.nextBoolean()) {
                                    BlockTransaction blkTx = recentTransactions.get(random.nextInt(recentTransactions.size()));
                                    String scriptHash = getScriptHash(blkTx.getTransaction().getOutputs().getFirst());
                                    String status = getScriptHashStatus(List.of(new ScriptHashTx(0, blkTx.getHashAsString(), blkTx.getFee())));
                                    broadcastRecent.put(scriptHash, status);
                                    electrumServer.broadcastTransaction(blkTx.getTransaction());
                                }
                            }
                            return null;
                        }
                    };
                }
            };
            broadcastService.setDelay(Duration.seconds(Math.random() * 60 * 10));
            broadcastService.setPeriod(Duration.hours(1));
            broadcastService.setOnSucceeded(_ -> broadcastService.cancel());
            broadcastService.setOnFailed(_ -> broadcastService.cancel());
            broadcastService.start();
        }
    }

    public static class WalletDiscoveryService extends Service<Optional<Wallet>> {
        private final List<Wallet> wallets;

        public WalletDiscoveryService(List<Wallet> wallets) {
            this.wallets = wallets;
        }

        @Override
        protected Task<Optional<Wallet>> createTask() {
            return new Task<>() {
                protected Optional<Wallet> call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();

                    for(int i = 0; i < wallets.size(); i++) {
                        Wallet wallet = wallets.get(i);
                        updateProgress(i, wallets.size() + StandardAccount.DISCOVERY_ACCOUNTS.size());
                        Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap = new TreeMap<>();
                        electrumServer.getReferences(wallet, wallet.getNode(KeyPurpose.RECEIVE).getChildren(), nodeTransactionMap, 0);
                        if(nodeTransactionMap.values().stream().anyMatch(blockTransactionHashes -> !blockTransactionHashes.isEmpty())) {
                            Wallet masterWalletCopy = wallet.copy();
                            List<StandardAccount> searchAccounts = getStandardAccounts(wallet);
                            Set<StandardAccount> foundAccounts = new LinkedHashSet<>();
                            for(int j = 0; j < searchAccounts.size(); j++) {
                                StandardAccount standardAccount = searchAccounts.get(j);
                                Wallet childWallet = masterWalletCopy.addChildWallet(standardAccount);
                                Map<WalletNode, Set<BlockTransactionHash>> childTransactionMap = new TreeMap<>();
                                electrumServer.getReferences(childWallet, childWallet.getNode(KeyPurpose.RECEIVE).getChildren(), childTransactionMap, 0);
                                if(childTransactionMap.values().stream().anyMatch(blockTransactionHashes -> !blockTransactionHashes.isEmpty())) {
                                    if(StandardAccount.isWhirlpoolAccount(standardAccount)) {
                                        foundAccounts.addAll(StandardAccount.WHIRLPOOL_ACCOUNTS);
                                    } else {
                                        foundAccounts.add(standardAccount);
                                    }
                                }
                                updateProgress(i + j, wallets.size() + StandardAccount.DISCOVERY_ACCOUNTS.size());
                            }

                            for(StandardAccount standardAccount : foundAccounts) {
                                wallet.addChildWallet(standardAccount);
                            }

                            return Optional.of(wallet);
                        }
                    }

                    return Optional.empty();
                }
            };
        }

        private List<StandardAccount> getStandardAccounts(Wallet wallet) {
            if(!wallet.getKeystores().stream().allMatch(Keystore::hasMasterPrivateKey)) {
                return Collections.emptyList();
            }

            List<StandardAccount> accounts = new ArrayList<>();
            for(StandardAccount account : StandardAccount.DISCOVERY_ACCOUNTS) {
                if(account != StandardAccount.ACCOUNT_0 && (!StandardAccount.isWhirlpoolAccount(account) || wallet.getScriptType() == ScriptType.P2WPKH)) {
                    accounts.add(account);
                }
            }

            return accounts;
        }
    }

    public static class AccountDiscoveryService extends Service<List<StandardAccount>> {
        private final Wallet masterWalletCopy;
        private final List<StandardAccount> standardAccounts;
        private final Map<StandardAccount, Keystore> importedKeystores;

        public AccountDiscoveryService(Wallet masterWallet, List<StandardAccount> standardAccounts) {
            this.masterWalletCopy = masterWallet.copy();
            this.standardAccounts = standardAccounts;
            this.importedKeystores = new HashMap<>();
        }

        public AccountDiscoveryService(Wallet masterWallet, Map<StandardAccount, Keystore> importedKeystores) {
            this.masterWalletCopy = masterWallet.copy();
            this.standardAccounts = new ArrayList<>(importedKeystores.keySet());
            this.importedKeystores = importedKeystores;
        }

        @Override
        protected Task<List<StandardAccount>> createTask() {
            return new Task<>() {
                protected List<StandardAccount> call() throws ServerException {
                    ElectrumServer electrumServer = new ElectrumServer();
                    List<StandardAccount> discoveredAccounts = new ArrayList<>();

                    for(StandardAccount standardAccount : standardAccounts) {
                        Wallet wallet = masterWalletCopy.addChildWallet(standardAccount);
                        if(importedKeystores.containsKey(standardAccount)) {
                            wallet.getKeystores().clear();
                            wallet.getKeystores().add(importedKeystores.get(standardAccount));
                        }

                        Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap = new TreeMap<>();
                        electrumServer.getReferences(wallet, wallet.getNode(KeyPurpose.RECEIVE).getChildren(), nodeTransactionMap, 0);
                        if(nodeTransactionMap.values().stream().anyMatch(blockTransactionHashes -> !blockTransactionHashes.isEmpty())) {
                            discoveredAccounts.add(standardAccount);
                        }
                    }

                    return discoveredAccounts;
                }
            };
        }
    }

    public static class AddressUtxosService extends Service<List<TransactionOutput>> {
        private final Address address;
        private final Date since;

        public AddressUtxosService(Address address, Date since) {
            this.address = address;
            this.since = since;
        }

        @Override
        protected Task<List<TransactionOutput>> createTask() {
            return new Task<>() {
                protected List<TransactionOutput> call() throws ServerException {
                    if(ElectrumServer.cormorant != null) {
                        updateProgress(-1, 0);
                        ElectrumServer.cormorant.checkAddressImport(address, since);
                    }

                    ElectrumServer electrumServer = new ElectrumServer();
                    return electrumServer.getUtxos(address);
                }
            };
        }
    }

    public static class PaymentCodesService extends Service<List<Wallet>> {
        private final String walletId;
        private final Wallet wallet;

        public PaymentCodesService(String walletId, Wallet wallet) {
            this.walletId = walletId;
            this.wallet = wallet;
        }

        @Override
        protected Task<List<Wallet>> createTask() {
            return new Task<>() {
                protected List<Wallet> call() throws ServerException {
                    if(ElectrumServer.cormorant != null) {
                        if(!ElectrumServer.cormorant.checkWalletImport(wallet)) {
                            return Collections.emptyList();
                        }
                    }

                    Wallet notificationWallet = wallet.getNotificationWallet();
                    WalletNode notificationNode = notificationWallet.getNode(KeyPurpose.NOTIFICATION);

                    for(Wallet childWallet : wallet.getChildWallets()) {
                        if(childWallet.isBip47()) {
                            WalletNode savedNotificationNode = childWallet.getNode(KeyPurpose.NOTIFICATION);
                            notificationNode.getTransactionOutputs().addAll(savedNotificationNode.getTransactionOutputs());
                            notificationWallet.updateTransactions(childWallet.getTransactions());
                        }
                    }

                    addCalculatedScriptHashes(notificationNode);

                    ElectrumServer electrumServer = new ElectrumServer();
                    Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap = electrumServer.getHistory(notificationWallet, List.of(notificationNode));
                    electrumServer.getReferencedTransactions(notificationWallet, nodeTransactionMap);
                    electrumServer.calculateNodeHistory(notificationWallet, nodeTransactionMap);

                    List<Wallet> addedWallets = new ArrayList<>();
                    if(!nodeTransactionMap.isEmpty()) {
                        Set<PaymentCode> paymentCodes = new LinkedHashSet<>();
                        for(BlockTransactionHashIndex output : notificationNode.getTransactionOutputs()) {
                            BlockTransaction blkTx = notificationWallet.getTransactions().get(output.getHash());
                            try {
                                PaymentCode paymentCode = PaymentCode.getPaymentCode(blkTx.getTransaction(), notificationWallet.getKeystores().get(0));
                                if(paymentCodes.add(paymentCode)) {
                                    if(getExistingChildWallet(paymentCode) == null) {
                                        PayNym payNym = Config.get().isUsePayNym() ? getPayNym(paymentCode) : null;
                                        List<ScriptType> scriptTypes = payNym == null || wallet.getScriptType() != ScriptType.P2PKH ? PayNym.getSegwitScriptTypes() : payNym.getScriptTypes();
                                        for(ScriptType childScriptType : scriptTypes) {
                                            String label = (payNym == null ? paymentCode.toAbbreviatedString() : payNym.nymName()) + " " + childScriptType.getName();
                                            Wallet addedWallet = wallet.addChildWallet(paymentCode, childScriptType, output, blkTx, label);
                                            //Check this is a valid payment code, will throw IllegalArgumentException if not
                                            try {
                                                WalletNode receiveNode = new WalletNode(addedWallet, KeyPurpose.RECEIVE, 0);
                                                receiveNode.getPubKey();
                                            } catch(IllegalArgumentException e) {
                                                wallet.getChildWallets().remove(addedWallet);
                                                throw e;
                                            }

                                            addedWallets.add(addedWallet);
                                        }
                                    }
                                }
                            } catch(InvalidPaymentCodeException e) {
                                log.info("Could not determine payment code for notification transaction", e);
                            } catch(IllegalArgumentException e) {
                                log.info("Invalid notification transaction creates illegal payment code", e);
                            }
                        }
                    }

                    return addedWallets;
                }
            };
        }

        private PayNym getPayNym(PaymentCode paymentCode) {
            try {
                return PayNymService.getPayNym(paymentCode.toString()).blockingFirst();
            } catch(Exception e) {
                //ignore
            }

            return null;
        }

        private Wallet getExistingChildWallet(PaymentCode paymentCode) {
            for(Wallet childWallet : wallet.getChildWallets()) {
                if(childWallet.isBip47() && paymentCode.equals(childWallet.getKeystores().get(0).getExternalPaymentCode())) {
                    return childWallet;
                }
            }

            return null;
        }
    }
}
