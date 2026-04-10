## POST http://localhost:38717/echo → 200 OK

### Request Headers
```
POST http://localhost:38717/echo
```

### Request Body
```json
{
  "name": "Eve",
  "action": "create"
}
```

### Response Headers
```
content-length: 72
content-type: application/json
date: Fri, 10 Apr 2026 22:29:03 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Eve",
    "action": "create"
  }
}
```

### cURL
```bash
curl 'http://localhost:38717/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
