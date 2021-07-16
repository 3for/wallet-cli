/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.core;

import org.tron.common.utils.DecodeUtil;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import org.tron.api.GrpcAPI;
import org.tron.api.GrpcAPI.ReceiveNote;
import org.tron.api.GrpcAPI.ShieldedTRC20Parameters;
import org.tron.api.GrpcAPI.PrivateShieldedTRC20Parameters;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ZksnarkException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.zen.ShieldedTRC20ParametersBuilder;
import org.tron.core.zen.ShieldedTRC20ParametersBuilder.ShieldedTRC20ParametersType;
import org.apache.commons.lang3.ArrayUtils;
import java.math.BigInteger;
import org.tron.core.zen.address.SpendingKey;
import org.tron.core.zen.address.ExpandedSpendingKey;
import org.tron.core.zen.address.PaymentAddress;
import java.util.Optional;
import org.tron.core.zen.note.NoteEncryption;
import org.tron.common.utils.ByteUtil;
import java.util.List;
import org.tron.core.zen.note.Note;
import org.tron.protos.contract.SmartContractOuterClass;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;
import org.tron.core.zen.address.KeyIo;
import java.util.Objects;
import org.tron.common.crypto.Hash;
import com.google.protobuf.ByteString;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.api.GrpcAPI.TransactionExtention.Builder;
import org.tron.api.GrpcAPI.Return;
import org.tron.api.GrpcAPI.Return.response_code;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.core.exception.VMIllegalException;
import org.tron.core.exception.HeaderNotFound;

import org.tron.walletserver.GrpcClient;
import org.tron.walletserver.WalletApi;
import org.junit.Assert;
import org.tron.api.GrpcAPI.PrivateShieldedTRC20ParametersWithoutAsk;

@Slf4j
@Component
public class Wallet {

  private static final String PAYMENT_ADDRESS_FORMAT_WRONG = "paymentAddress format is wrong";
  public static final String CONTRACT_VALIDATE_ERROR = "contract validate error : ";
  public static final String CONTRACT_VALIDATE_EXCEPTION = "ContractValidateException: {}";


  private GrpcClient grpcClient = WalletApi.init();

  public static byte getAddressPreFixByte() {
    return DecodeUtil.addressPreFixByte;
  }

  private void checkShieldedTRC20NoteValue(
      List<GrpcAPI.SpendNoteTRC20> spendNoteTRC20s, List<ReceiveNote> receiveNotes)
      throws ContractValidateException {
    if (!Objects.isNull(spendNoteTRC20s)) {
      for (GrpcAPI.SpendNoteTRC20 spendNote : spendNoteTRC20s) {
        if (spendNote.getNote().getValue() < 0) {
          throw new ContractValidateException("The value in SpendNoteTRC20 must >= 0");
        }
      }
    }

    if (!Objects.isNull(receiveNotes)) {
      for (ReceiveNote receiveNote : receiveNotes) {
        if (receiveNote.getNote().getValue() < 0) {
          throw new ContractValidateException("The value in ReceiveNote must >= 0");
        }
      }
    }
  }

  private void buildShieldedTRC20Output(ShieldedTRC20ParametersBuilder builder,
      ReceiveNote receiveNote, byte[] ovk) throws ZksnarkException {
    PaymentAddress paymentAddress = KeyIo.decodePaymentAddress(
        receiveNote.getNote().getPaymentAddress());
    if (Objects.isNull(paymentAddress)) {
      throw new ZksnarkException(PAYMENT_ADDRESS_FORMAT_WRONG);
    }

    builder.addOutput(ovk, paymentAddress.getD(), paymentAddress.getPkD(),
        receiveNote.getNote().getValue(), receiveNote.getNote().getRcm().toByteArray(),
        receiveNote.getNote().getMemo().toByteArray());
  }

  private void buildShieldedTRC20Input(ShieldedTRC20ParametersBuilder builder,
      GrpcAPI.SpendNoteTRC20 spendNote, ExpandedSpendingKey expsk)
      throws ZksnarkException {
    GrpcAPI.Note note = spendNote.getNote();
    PaymentAddress paymentAddress = KeyIo.decodePaymentAddress(note.getPaymentAddress());
    if (Objects.isNull(paymentAddress)) {
      throw new ZksnarkException(PAYMENT_ADDRESS_FORMAT_WRONG);
    }

    Note baseNote = new Note(paymentAddress.getD(),
        paymentAddress.getPkD(),
        note.getValue(),
        note.getRcm().toByteArray());
    builder.addSpend(expsk,
        baseNote,
        spendNote.getAlpha().toByteArray(),
        spendNote.getRoot().toByteArray(),
        spendNote.getPath().toByteArray(),
        spendNote.getPos());
  }


  private byte[] triggerGetScalingFactor(byte[] contractAddress) {
    String methodSign = "scalingFactor()";
    byte[] selector = new byte[4];
    System.arraycopy(Hash.sha3(methodSign.getBytes()), 0, selector, 0, 4);
    SmartContractOuterClass.TriggerSmartContract.Builder triggerBuilder = SmartContractOuterClass
        .TriggerSmartContract.newBuilder();
    triggerBuilder.setContractAddress(ByteString.copyFrom(contractAddress));
    triggerBuilder.setData(ByteString.copyFrom(selector));
    GrpcAPI.TransactionExtention trxExt2 = grpcClient.triggerConstantContract(
        triggerBuilder.build());
    List<ByteString> list = trxExt2.getConstantResultList();
    byte[] result = new byte[0];
    for (ByteString bs : list) {
      result = ByteUtil.merge(result, bs.toByteArray());
    }
    Assert.assertEquals(32, result.length);
    return result;
  }

  private void checkBigIntegerRange(BigInteger in) throws ContractValidateException {
    if (in.compareTo(BigInteger.ZERO) < 0) {
      throw new ContractValidateException("public amount must be non-negative");
    }
    if (in.bitLength() > 256) {
      throw new ContractValidateException("public amount must be no more than 256 bits");
    }
  }

  private BigInteger getBigIntegerFromString(String in) {
    String trimmedIn = in.trim();
    if (trimmedIn.length() == 0) {
      return BigInteger.ZERO;
    }
    return new BigInteger(trimmedIn, 10);
  }

  /**
   * trigger contract to get the scalingFactor, and check the public amount,
   */
  private long[] checkPublicAmount(byte[] address, BigInteger fromAmount, BigInteger toAmount)
      throws ContractExeException, ContractValidateException {
    checkBigIntegerRange(fromAmount);
    checkBigIntegerRange(toAmount);

    BigInteger scalingFactor;
    
    byte[] scalingFactorBytes = triggerGetScalingFactor(address);
    scalingFactor = ByteUtil.bytesToBigInteger(scalingFactorBytes);
   

    // fromAmount and toAmount must be a multiple of scalingFactor
    if (!(fromAmount.mod(scalingFactor).equals(BigInteger.ZERO)
        && toAmount.mod(scalingFactor).equals(BigInteger.ZERO))) {
      throw new ContractValidateException("fromAmount or toAmount invalid");
    }

    long[] ret = new long[2];
    try {
      ret[0] = fromAmount.divide(scalingFactor).longValueExact();
      ret[1] = toAmount.divide(scalingFactor).longValueExact();
    } catch (ArithmeticException e) {
      throw new ContractValidateException("fromAmount or toAmount invalid");
    }

    return ret;
  }

  private void buildShieldedTRC20InputWithAK(
      ShieldedTRC20ParametersBuilder builder, GrpcAPI.SpendNoteTRC20 spendNote,
      byte[] ak, byte[] nsk) throws ZksnarkException {
    GrpcAPI.Note note = spendNote.getNote();
    PaymentAddress paymentAddress = KeyIo.decodePaymentAddress(note.getPaymentAddress());
    if (Objects.isNull(paymentAddress)) {
      throw new ZksnarkException(PAYMENT_ADDRESS_FORMAT_WRONG);
    }

    Note baseNote = new Note(paymentAddress.getD(),
        paymentAddress.getPkD(), note.getValue(), note.getRcm().toByteArray());
    builder.addSpend(ak,
        nsk,
        baseNote,
        spendNote.getAlpha().toByteArray(),
        spendNote.getRoot().toByteArray(),
        spendNote.getPath().toByteArray(),
        spendNote.getPos());
  }

  public ShieldedTRC20Parameters zydcreateShieldedContractParametersWithoutAsk(
      PrivateShieldedTRC20ParametersWithoutAsk request)
      throws ZksnarkException, ContractValidateException, ContractExeException {
    //checkFullNodeAllowShieldedTransaction(); //zyd. Don't judge it now.

    ShieldedTRC20ParametersBuilder builder = new ShieldedTRC20ParametersBuilder();
    byte[] shieldedTRC20ContractAddress = request.getShieldedTRC20ContractAddress().toByteArray();
    if (ArrayUtils.isEmpty(shieldedTRC20ContractAddress)
        || shieldedTRC20ContractAddress.length != 21) {
      throw new ContractValidateException("No valid shielded TRC-20 contract address");
    }
    byte[] shieldedTRC20ContractAddressTvm = new byte[20];
    System.arraycopy(shieldedTRC20ContractAddress, 1, shieldedTRC20ContractAddressTvm, 0, 20);
    builder.setShieldedTRC20Address(shieldedTRC20ContractAddressTvm);

    BigInteger fromAmount;
    BigInteger toAmount;
    try {
      fromAmount = getBigIntegerFromString(request.getFromAmount());
      toAmount = getBigIntegerFromString(request.getToAmount());
    } catch (Exception e) {
      throw new ContractValidateException("invalid_from amount or to_amount");
    }
    long[] scaledPublicAmount = checkPublicAmount(shieldedTRC20ContractAddress,
        fromAmount, toAmount);
    long scaledFromAmount = scaledPublicAmount[0];
    long scaledToAmount = scaledPublicAmount[1];

    List<GrpcAPI.SpendNoteTRC20> shieldedSpends = request.getShieldedSpendsList();
    int spendSize = shieldedSpends.size();
    List<ReceiveNote> shieldedReceives = request.getShieldedReceivesList();
    int receiveSize = shieldedReceives.size();
    checkShieldedTRC20NoteValue(shieldedSpends, shieldedReceives);
    long totalToAmount = 0;
    if (scaledToAmount > 0) {
      try {
        totalToAmount = receiveSize == 0 ? scaledToAmount
            : Math.addExact(scaledToAmount, shieldedReceives.get(0).getNote().getValue());
      } catch (ArithmeticException e) {
        throw new ZksnarkException("Unbalanced burn!");
      }
    }

    if (scaledFromAmount > 0 && spendSize == 0 && receiveSize == 1
        && scaledFromAmount == shieldedReceives.get(0).getNote().getValue()
        && scaledToAmount == 0) {
      byte[] ovk = request.getOvk().toByteArray();
      if (ArrayUtils.isEmpty(ovk)) {
        ovk = SpendingKey.random().fullViewingKey().getOvk();
      }
      builder.setShieldedTRC20ParametersType(ShieldedTRC20ParametersType.MINT);
      builder.setTransparentFromAmount(fromAmount);
      ReceiveNote receiveNote = shieldedReceives.get(0);
      buildShieldedTRC20Output(builder, receiveNote, ovk);
    } else if (scaledFromAmount == 0 && spendSize > 0 && spendSize < 3
        && receiveSize > 0 && receiveSize < 3 && scaledToAmount == 0) {
      builder.setShieldedTRC20ParametersType(ShieldedTRC20ParametersType.TRANSFER);
      byte[] ak = request.getAk().toByteArray();
      byte[] nsk = request.getNsk().toByteArray();
      byte[] ovk = request.getOvk().toByteArray();
      if ((ArrayUtils.isEmpty(ak) || ArrayUtils.isEmpty(nsk) || ArrayUtils.isEmpty(ovk))) {
        throw new ContractValidateException("No shielded TRC-20 ak, nsk or ovk");
      }
      for (GrpcAPI.SpendNoteTRC20 spendNote : shieldedSpends) {
        buildShieldedTRC20InputWithAK(builder, spendNote, ak, nsk);
      }
      for (ReceiveNote receiveNote : shieldedReceives) {
        buildShieldedTRC20Output(builder, receiveNote, ovk);
      }
    } else if (scaledFromAmount == 0 && spendSize == 1 && receiveSize >= 0 && receiveSize <= 1
        && scaledToAmount > 0 && totalToAmount == shieldedSpends.get(0).getNote().getValue()) {
      builder.setShieldedTRC20ParametersType(ShieldedTRC20ParametersType.BURN);
      byte[] ak = request.getAk().toByteArray();
      byte[] nsk = request.getNsk().toByteArray();
      byte[] ovk = request.getOvk().toByteArray();
      if ((ArrayUtils.isEmpty(ak) || ArrayUtils.isEmpty(nsk) || ArrayUtils.isEmpty(ovk))) {
        throw new ContractValidateException("No shielded TRC-20 ak, nsk or ovk");
      }
      byte[] transparentToAddress = request.getTransparentToAddress().toByteArray();
      if (ArrayUtils.isEmpty(transparentToAddress) || transparentToAddress.length != 21) {
        throw new ContractValidateException("No transparent TRC-20 output address");
      }
      byte[] transparentToAddressTvm = new byte[20];
      System.arraycopy(transparentToAddress, 1, transparentToAddressTvm, 0, 20);
      builder.setTransparentToAddress(transparentToAddressTvm);
      builder.setTransparentToAmount(toAmount);
      Optional<byte[]> cipher = NoteEncryption.Encryption
          .encryptBurnMessageByOvk(ovk, toAmount, transparentToAddress);
      cipher.ifPresent(builder::setBurnCiphertext);
      GrpcAPI.SpendNoteTRC20 spendNote = shieldedSpends.get(0);
      buildShieldedTRC20InputWithAK(builder, spendNote, ak, nsk);
      if (receiveSize == 1) {
        buildShieldedTRC20Output(builder, shieldedReceives.get(0), ovk);
      }
    } else {
      throw new ContractValidateException("invalid shielded TRC-20 parameters");
    }
    return builder.build(false);
  }

   public ShieldedTRC20Parameters zydcreateShieldedContractParameters(
      PrivateShieldedTRC20Parameters request)
      throws ContractValidateException, ZksnarkException, ContractExeException {
    //checkFullNodeAllowShieldedTransaction(); //zyd. Don't judge it now.
    System.out.println("zyd 1111");
    ShieldedTRC20ParametersBuilder builder = new ShieldedTRC20ParametersBuilder();

    byte[] shieldedTRC20ContractAddress = request.getShieldedTRC20ContractAddress().toByteArray();
    if (ArrayUtils.isEmpty(shieldedTRC20ContractAddress)
        || shieldedTRC20ContractAddress.length != 21) {
      throw new ContractValidateException("No valid shielded TRC-20 contract address");
    }

    byte[] shieldedTRC20ContractAddressTvm = new byte[20];
    System.arraycopy(shieldedTRC20ContractAddress, 1, shieldedTRC20ContractAddressTvm, 0, 20);
    builder.setShieldedTRC20Address(shieldedTRC20ContractAddressTvm);

    BigInteger fromAmount;
    BigInteger toAmount;
    try {
      fromAmount = getBigIntegerFromString(request.getFromAmount());
      toAmount = getBigIntegerFromString(request.getToAmount());
    } catch (Exception e) {
      throw new ContractValidateException("invalid from_amount or to_amount");
    }

    long[] scaledPublicAmount = checkPublicAmount(shieldedTRC20ContractAddress,
        fromAmount, toAmount);
    long scaledFromAmount = scaledPublicAmount[0];
    long scaledToAmount = scaledPublicAmount[1];

    List<GrpcAPI.SpendNoteTRC20> shieldedSpends = request.getShieldedSpendsList();
    List<ReceiveNote> shieldedReceives = request.getShieldedReceivesList();
    checkShieldedTRC20NoteValue(shieldedSpends, shieldedReceives);

    int spendSize = shieldedSpends.size();
    int receiveSize = shieldedReceives.size();
    long totalToAmount = 0;
    if (scaledToAmount > 0) {
      try {
        totalToAmount = receiveSize == 0 ? scaledToAmount
            : (Math.addExact(scaledToAmount, shieldedReceives.get(0).getNote().getValue()));
      } catch (ArithmeticException e) {
        throw new ZksnarkException("Unbalanced burn!");
      }
    }

    if (scaledFromAmount > 0 && spendSize == 0 && receiveSize == 1
        && scaledFromAmount == shieldedReceives.get(0).getNote().getValue()
        && scaledToAmount == 0) {
      builder.setShieldedTRC20ParametersType(ShieldedTRC20ParametersType.MINT);

      byte[] ovk = request.getOvk().toByteArray();
      if (ArrayUtils.isEmpty(ovk)) {
        ovk = SpendingKey.random().fullViewingKey().getOvk();
      }

      builder.setTransparentFromAmount(fromAmount);
      buildShieldedTRC20Output(builder, shieldedReceives.get(0), ovk);
    } else if (scaledFromAmount == 0 && spendSize > 0 && spendSize < 3
        && receiveSize > 0 && receiveSize < 3 && scaledToAmount == 0) {
      builder.setShieldedTRC20ParametersType(ShieldedTRC20ParametersType.TRANSFER);

      byte[] ask = request.getAsk().toByteArray();
      byte[] nsk = request.getNsk().toByteArray();
      byte[] ovk = request.getOvk().toByteArray();
      if ((ArrayUtils.isEmpty(ask) || ArrayUtils.isEmpty(nsk) || ArrayUtils.isEmpty(ovk))) {
        throw new ContractValidateException("No shielded TRC-20 ask, nsk or ovk");
      }

      ExpandedSpendingKey expsk = new ExpandedSpendingKey(ask, nsk, ovk);
      for (GrpcAPI.SpendNoteTRC20 spendNote : shieldedSpends) {
        buildShieldedTRC20Input(builder, spendNote, expsk);
      }

      for (ReceiveNote receiveNote : shieldedReceives) {
        buildShieldedTRC20Output(builder, receiveNote, ovk);
      }
    } else if (scaledFromAmount == 0 && spendSize == 1 && receiveSize >= 0 && receiveSize <= 1
        && scaledToAmount > 0 && totalToAmount == shieldedSpends.get(0).getNote().getValue()) {
      builder.setShieldedTRC20ParametersType(ShieldedTRC20ParametersType.BURN);

      byte[] ask = request.getAsk().toByteArray();
      byte[] nsk = request.getNsk().toByteArray();
      byte[] ovk = request.getOvk().toByteArray();
      if ((ArrayUtils.isEmpty(ask) || ArrayUtils.isEmpty(nsk) || ArrayUtils.isEmpty(ovk))) {
        throw new ContractValidateException("No shielded TRC-20 ask, nsk or ovk");
      }

      byte[] transparentToAddress = request.getTransparentToAddress().toByteArray();
      if (ArrayUtils.isEmpty(transparentToAddress) || transparentToAddress.length != 21) {
        throw new ContractValidateException("No valid transparent TRC-20 output address");
      }

      byte[] transparentToAddressTvm = new byte[20];
      System.arraycopy(transparentToAddress, 1, transparentToAddressTvm, 0, 20);
      builder.setTransparentToAddress(transparentToAddressTvm);
      builder.setTransparentToAmount(toAmount);

      Optional<byte[]> cipher = NoteEncryption.Encryption
          .encryptBurnMessageByOvk(ovk, toAmount, transparentToAddress);
      cipher.ifPresent(builder::setBurnCiphertext);

      ExpandedSpendingKey expsk = new ExpandedSpendingKey(ask, nsk, ovk);
      GrpcAPI.SpendNoteTRC20 spendNote = shieldedSpends.get(0);
      buildShieldedTRC20Input(builder, spendNote, expsk);
      if (receiveSize == 1) {
        buildShieldedTRC20Output(builder, shieldedReceives.get(0), ovk);
      }
    } else {
      throw new ContractValidateException("invalid shielded TRC-20 parameters");
    }

    return builder.build(true);
  }
}

