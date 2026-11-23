## PATCH http://localhost:44161/echo → 200 OK

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
date: Mon, 23 Nov 2026 10:51:55 GMT
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
curl 'http://localhost:44161/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
