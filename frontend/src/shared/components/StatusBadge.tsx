import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { Colors } from '../../constants/colors';
import { ProductStatus } from '../../types';

interface Props {
  status: ProductStatus;
  stock: number;
}

export const StatusBadge = React.memo(({ status, stock }: Props) => {
  const config = {
    available: { text: '신청 가능', bg: Colors.greenBg, color: Colors.brandGreen },
    low_stock: { text: `잔여 ${stock}개`, bg: Colors.orangeBg, color: Colors.orange },
    sold_out: { text: '품절', bg: Colors.redBg, color: Colors.red },
  }[status];

  return (
    <View style={[styles.badge, { backgroundColor: config.bg }]}>
      <Text style={[styles.text, { color: config.color }]}>{config.text}</Text>
    </View>
  );
});

const styles = StyleSheet.create({
  badge: {
    height: 20,
    paddingHorizontal: 7,
    paddingVertical: 3,
    borderRadius: 20,
    alignSelf: 'flex-start',
  },
  text: {
    fontSize: 9,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
});
