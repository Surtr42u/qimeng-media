package deviceapply

// transitions is the device apply state machine. It is deliberately explicit
// so an invalid edge is a compile-time-readable omission and the table doubles
// as the contract for the future Android worker:
//
//	pending  -> claimed | cancelled | expired
//	claimed  -> applied | partial | failed | cancelled | expired
//
// Terminal states have no outgoing edges.
var transitions = map[Status]map[Status]struct{}{
	StatusPending: {
		StatusClaimed:   {},
		StatusCancelled: {},
		StatusExpired:   {},
	},
	StatusClaimed: {
		StatusApplied:   {},
		StatusPartial:   {},
		StatusFailed:    {},
		StatusCancelled: {},
		StatusExpired:   {},
	},
}

// CanTransitionTo reports whether the state machine allows from -> next.
func (s Status) CanTransitionTo(next Status) bool {
	if !s.Valid() || !next.Valid() || s == next || s.Terminal() {
		return false
	}
	_, ok := transitions[s][next]
	return ok
}
