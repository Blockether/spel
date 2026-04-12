## PUT http://localhost:33355/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Sun, 12 Apr 2026 19:07:35 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:33355/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
