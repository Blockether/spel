## DELETE http://localhost:41621/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 20 Jul 2026 16:58:53 GMT
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
curl 'http://localhost:41621/echo' \
  -X DELETE
```
