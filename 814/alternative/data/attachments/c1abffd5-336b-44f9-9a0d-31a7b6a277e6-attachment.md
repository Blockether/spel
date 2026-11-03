## PATCH http://localhost:43419/echo → 200 OK

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Tue, 03 Nov 2026 22:22:51 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:43419/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
