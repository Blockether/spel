## DELETE http://localhost:38071/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 20 Jul 2026 17:33:03 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:38071/echo' \
  -X DELETE
```
