## PUT http://localhost:33433/echo → 200 OK

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
date: Fri, 20 Nov 2026 07:44:19 GMT
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
curl 'http://localhost:33433/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
