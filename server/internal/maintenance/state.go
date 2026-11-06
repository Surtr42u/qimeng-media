package maintenance

// transitions is the worker-facing state machine. It is deliberately explicit
// (instead of a numeric graph) so an invalid edge is a compile-time-readable
// omission, and the table doubles as the contract for the future worker.
//
// waiting_* states can re-enter the pipeline at queued/inventory or move
// forward when the missing evidence/authorization/device arrives. paused only
// resumes to queued (the worker restarts at inventory); terminal states have no
// outgoing edges.
var transitions = map[Status]map[Status]struct{}{
	StatusQueued: {
		StatusInventory: {}, StatusPaused: {}, StatusCancelled: {},
		StatusFailed: {}, StatusWaitingEvidence: {},
		StatusWaitingAuthorization: {}, StatusWaitingDevice: {},
	},
	StatusInventory: {
		StatusResearch: {}, StatusPlanned: {}, StatusPaused: {},
		StatusCancelled: {}, StatusFailed: {}, StatusWaitingEvidence: {},
		StatusWaitingAuthorization: {}, StatusWaitingDevice: {},
	},
	StatusResearch: {
		StatusPlanned: {}, StatusInventory: {}, StatusPaused: {},
		StatusCancelled: {}, StatusFailed: {}, StatusWaitingEvidence: {},
		StatusWaitingAuthorization: {}, StatusWaitingDevice: {},
	},
	StatusPlanned: {
		StatusApplying: {}, StatusResearch: {}, StatusPaused: {},
		StatusCancelled: {}, StatusFailed: {}, StatusWaitingAuthorization: {},
		StatusWaitingDevice: {},
	},
	StatusApplying: {
		StatusVerifying: {}, StatusPartial: {}, StatusPaused: {},
		StatusCancelled: {}, StatusFailed: {}, StatusWaitingAuthorization: {},
		StatusWaitingDevice: {},
	},
	StatusVerifying: {
		StatusCompleted: {}, StatusPartial: {}, StatusPaused: {},
		StatusCancelled: {}, StatusFailed: {}, StatusWaitingEvidence: {},
	},
	StatusWaitingEvidence: {
		StatusQueued: {}, StatusInventory: {}, StatusResearch: {},
		StatusPlanned: {}, StatusPaused: {}, StatusCancelled: {},
		StatusFailed: {},
	},
	StatusWaitingAuthorization: {
		StatusQueued: {}, StatusInventory: {}, StatusResearch: {},
		StatusPlanned: {}, StatusApplying: {}, StatusPaused: {},
		StatusCancelled: {}, StatusFailed: {},
	},
	StatusWaitingDevice: {
		StatusQueued: {}, StatusInventory: {}, StatusResearch: {},
		StatusPlanned: {}, StatusApplying: {}, StatusPaused: {},
		StatusCancelled: {}, StatusFailed: {},
	},
	StatusPaused: {
		StatusQueued: {}, StatusCancelled: {}, StatusFailed: {},
	},
}

// CanTransitionTo reports whether the worker-facing state machine allows
// from -> next. Same-state updates and edges out of terminal states are false.
func (s Status) CanTransitionTo(next Status) bool {
	if !s.Valid() || !next.Valid() || s == next || s.Terminal() {
		return false
	}
	_, ok := transitions[s][next]
	return ok
}

// progressFor turns indexed step counts into the list summary percentage.
// A terminal completed job is 100%; an empty active job is 0%. The value is a
// floor of completed/total, capped at 100.
func progressFor(status Status, total, completed int) int {
	if total <= 0 {
		if status == StatusCompleted {
			return 100
		}
		return 0
	}
	progress := completed * 100 / total
	if status == StatusCompleted || progress > 100 {
		return 100
	}
	if progress < 0 {
		return 0
	}
	return progress
}
