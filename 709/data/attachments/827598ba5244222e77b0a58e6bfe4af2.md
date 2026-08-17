## DELETE http://localhost:38425/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 17 Aug 2026 07:15:08 GMT
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
curl 'http://localhost:38425/echo' \
  -X DELETE
```
